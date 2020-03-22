import io.netty.util.concurrent.DefaultThreadFactory
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import reactor.core.publisher.UnicastProcessor
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.random.Random

/*

This code snippet represents example of communication with remote API using Reactive Streams.

Our goal is to download in parallel following hierarchy of objects with one-to-many relationship:

Report -> Campaign -> Ads

Campaigns and Ads are returned instantly by the API.

Reports have to be requested but will be ready some time in future thus we have to poll the API.

Parallelism is defined by number of threads in used schedulers/executors but can be customized.

*/


///// Fake model classes representing data we're getting from API

data class Ad(val id: Long, val name: String)
data class Campaign(val adsIds: List<Long>)
data class Report(val campaignIds: List<Long>)

///// Events that main processing stream supports

interface Command
data class FetchReport(val id: String) : Command
data class FetchCampaign(val id: Long) : Command
data class FetchAds(val ids: List<Long>) : Command
data class IngestAds(val items: List<Ad>) : Command

///// Util functions

val threadPool: Executor = Executors.newFixedThreadPool(8, DefaultThreadFactory("report"))
val myScheduler = Schedulers.fromExecutor(threadPool)

// Executor that executes Runnable on calling thread
object CurrentThreadExecutor : Executor {
    override fun execute(r: Runnable) {
        r.run()
    }
}

fun log(message: String) {
    println("[${Thread.currentThread().name}] [${LocalTime.now()}]: $message")
}

// For demonstration purposes fake Report ID contains date of creation which we use to derive IDs of
// descendant objects such as campaigns and ads. This method parses epoch inside Report ID
fun reportIdToEpoch(id: String): Instant {
    val (_, _, epoch) = id.split('~')
    return Instant.ofEpochMilli(epoch.toLong())
}

// Random sleep function to simulate delay when calling to remote API
val random = Random(System.currentTimeMillis())
fun sleepRandomly(maxMsTime: Long): Long {
    val ms = random.nextLong(maxMsTime)
    Thread.sleep(ms)
    return ms
}

///// Fake API functions (currently executed on calling thread)

// Returns fake ID of requested report
fun requestReport(date: LocalDate): CompletableFuture<String> {
    return CompletableFuture.supplyAsync({
        val reqTime = sleepRandomly(500)
        log("Requesting report for $date (took $reqTime ms)")
        "ID~$date~${System.currentTimeMillis()}"
    }, CurrentThreadExecutor)
}

// Checks if report is ready. Fake time required for remote service to build the report is derived from Report ID
fun isReportReady(reportId: String): CompletableFuture<Boolean> {
    return CompletableFuture.supplyAsync({
        val epoch = reportIdToEpoch(reportId)
        val msUntilReady = ((epoch.toEpochMilli() % 10) + 1) * 1000 // How long it takes to build report

        val reqTime = sleepRandomly(100)

        val interval = ChronoUnit.MILLIS.between(epoch, Instant.now())
        val isReady = interval >= msUntilReady
        log("Checking report $reportId (took $reqTime ms) [Target delay $msUntilReady ms][$interval ms since request => $isReady]")
        isReady
    }, CurrentThreadExecutor)
}

fun downloadReport(reportId: String): CompletableFuture<Report> {
    return CompletableFuture.supplyAsync({
        // Generate report with fake Campaign IDs
        val epoch = reportIdToEpoch(reportId)
        val prefix = epoch.toEpochMilli() % 10

        // Campaign IDs depend on Report ID so we can distinguish them in logs
        val rep = Report((1 until 5).map { it * prefix }.toList())

        val reqTime = sleepRandomly(100)

        log("Downloaded report: $reportId -> $rep (took $reqTime ms)")
        rep
    }, CurrentThreadExecutor)
}

fun downloadCampaign(id: Long): CompletableFuture<Campaign> {
    return CompletableFuture.supplyAsync({
        // Generate campaign with fake ad IDs which depend on Campaign ID so we can distinguish them in logs
        val camp = Campaign((1 until (Random(15L).nextLong(5) + 1)).map { it * 10 * id }.toList())

        val reqTime = sleepRandomly(100)

        log("Downloaded Campaign: $id -> $camp (took $reqTime ms)")

        camp
    }, CurrentThreadExecutor)
}

fun downloadAds(ids: List<Long>): CompletableFuture<List<Ad>> {
    return CompletableFuture.supplyAsync({
        log("Download ads: $ids")

        val reqTime = sleepRandomly(100)

        ids.map { Ad(it, "Ad $it (took $reqTime ms)") }
    }, CurrentThreadExecutor)
}

/////////////////////////////////////////////////////////////////////////////////////////////////////////

// This stream is constantly running on separate thread(s) until all requested reports are ready to be downloaded
fun requestAndPollReports(dates: List<LocalDate>, sink: FluxSink<Command>, pollingIntervalSec: Long, scheduler: Scheduler): Disposable {

    return Flux.fromIterable(dates)
            .parallel() // Parallelize requesting reports on provided scheduler
            .runOn(scheduler)
            .flatMap { date ->
                requestReport(date).toMono().map { reportId ->
                    // As soon as we get Report ID we set time in future when we want to check if it's ready
                    reportId to Instant.now().plusSeconds(pollingIntervalSec)
                }
            }
            .sequential() // Merge parallelized Report IDs into single sequence
            .expandDeep { (reportId, checkTime) ->

                // Somehow we need to keep checking X number of reports until they are ready which means
                // this stream is a stream of unknown but finite number of elements.

                // expand()/expandDeep() allows us to "requeue" report ID which is not yet ready.

                if (checkTime.isBefore(Instant.now())) {
                    isReportReady(reportId).toMono().flatMap { isReady ->
                        if (isReady) {
                            log("   ==> Report $reportId is ready")
                            sink.next(FetchReport(reportId))
                            Mono.empty()
                        } else {
                            log("   --> Report $reportId is NOT ready")
                            Mono.just(reportId to Instant.now().plusSeconds(pollingIntervalSec))
                        }
                    }.subscribeOn(scheduler)
                } else
                    Mono.just(reportId to checkTime)
            }
            .delayUntil { (_, checkTime) ->
                // To avoid billions of messages coming from `expandDeep()` wait until check time of any element
                val inter = ChronoUnit.MILLIS.between(Instant.now(), checkTime)
                if (inter > 0)
                    Mono.delay(Duration.ofMillis(inter))
                else
                    Mono.empty()
            }
            .doOnError { ex ->
                sink.error(ex)
            }
            .doOnComplete {
                log("All reports are ready.".toUpperCase())
                sink.complete()
            }
            .subscribe()
}

fun main() {

    val p = UnicastProcessor.create<Command>()
    val sinkReports = p.sink(FluxSink.OverflowStrategy.BUFFER)

    // Start date takes from connector's state object
    val startDate = LocalDate.now().minusDays(3)

    val interval = ChronoUnit.DAYS.between(startDate, LocalDate.now()) + 1
    val dates = (0 until interval).map { shift -> startDate.plusDays(shift) }.toList()

    // This Flux will request reports and will be polling job status until reports are ready
    log("Fetching ${dates.size} reports")
    val reportPollingThread = requestAndPollReports(dates, sinkReports, 5, myScheduler)

    // This is main Flux that processes ready-to-download report and downloads its descendant objects.

    // It's counter-intuitive but this whole Flux will be executed on thread
    //  where `sinkReports.next` was called unless we specifically switch
    //  scheduler inside expandDeep().
    log("Launching main Flux")
    (p as Flux<Command>)
            .expandDeep { command ->

                // Process data and request descendant objects if necessary

                val work =
                        Flux.defer {
                            when (command) {
                                is FetchReport ->
                                    downloadReport(command.id).toMono()
                                            .flatMapIterable { r -> r.campaignIds }
                                            .map { campaignId -> FetchCampaign(campaignId) }
                                is FetchCampaign ->
                                    downloadCampaign(command.id).toMono()
                                            .map { r -> FetchAds(r.adsIds) }
                                is FetchAds ->
                                    downloadAds(command.ids).toMono()
                                            .map { IngestAds(it) }
                                else ->
                                    // IngestAds() is caught here but we do not expand on it
                                    Mono.empty()
                            }
                        }

                // Schedule each work on pool of threads
                work.toFlux().subscribeOn(Schedulers.parallel())
            }
            .filter { cmd ->
                // Do we need to ingest Report and Campaign? For this demo we assume only Ads are ingested
                cmd is IngestAds
            }
            .doOnNext { value ->
                log("PROCESSED ADS: $value")
            }
            .doOnError { ex ->
                ex.printStackTrace()
            }
            .doOnTerminate {
                myScheduler.dispose()
                log("Terminated!")
            }
            .count()
            .blockOptional()
}
