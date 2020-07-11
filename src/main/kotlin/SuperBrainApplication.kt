package com.superbrain

import com.google.gson.Gson
import com.grum.geocalc.Coordinate
import com.grum.geocalc.EarthCalc
import com.grum.geocalc.Point
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.springframework.boot.Banner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestTemplate
import reactor.core.publisher.DirectProcessor
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxProcessor
import reactor.core.publisher.FluxSink
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*


@SpringBootApplication
@RestController
@RequestMapping("/")
open class SuperBrainApplication {

    val baseUrl = "http://34.71.217.0:5000"
    val gson = Gson()
    val config = Properties().apply {
        put(StreamsConfig.APPLICATION_ID_CONFIG, "superbrain${Random().nextInt()}")
        put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-demo-parametrix-b70f.aivencloud.com:12744")
//        put(StreamsConfig.STATE_DIR_CONFIG, "C:\\tmp") // on Windows
        put(StreamsConfig.STATE_DIR_CONFIG, "/tmp") // on Linux
        put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().javaClass)  //string casting
        put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().javaClass)  //string casting
        put("processing.guarantee", "exactly_once")
        put("security.protocol", "SSL")
        put("ssl.endpoint.identification.algorithm", "")
        put("ssl.truststore.location", "client.truststore.jks")
        put("ssl.truststore.password", "superbrain")
        put("ssl.keystore.type", "PKCS12")
        put("ssl.keystore.location", "client.keystore.p12")
        put("ssl.keystore.password", "superbrain")
        put("ssl.key.password", "superbrain")
    }

    val userEventFluxMap = mutableMapOf<String, Pair<FluxProcessor<String, String>, FluxSink<String>>>()
    val userNotificationFluxMap = mutableMapOf<String, Pair<FluxProcessor<String, String>, FluxSink<String>>>()

    @Suppress("UNCHECKED_CAST", "RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    val builder = StreamsBuilder().apply {
        stream<String, String>("event").foreach { _, value ->
            println(value)

            // Convert value to event
            val event = (gson.fromJson(value, Map::class.java) as Map<String, Any>).let { json ->
                object : Any() {
                    val userId = json["user_id"]
                    val type = json["type"]
                    val data = json["data"] as Map<String, Any>
                }
            }

            //Sent event to relevant flux
            userEventFluxMap[event.userId]?.second?.next(value)


            //fetch all notifications
            val notifications = RestTemplate()
                .getForObject(
                    "$baseUrl/notification?user_id=${event.userId}&type=${event.type}&is_active=true&size=1000",
                    List::class.java
                ).map { json ->
                    val tmpJson = json as Map<String, Any>
                    Notification(
                        tmpJson["id"] as String,
                        tmpJson["data"] as Map<String, Any>,
                        tmpJson)
                }

            when (event.type) {
                "Location" -> {
                    val lat = Coordinate.fromDegrees(event.data["lat"] as Double)
                    val lng = Coordinate.fromDegrees(event.data["lng"] as Double)
                    val eventPoint = Point.at(lat, lng)

                    notifications.filter { n ->
                        val tempLat = Coordinate.fromDegrees(n.data["lat"] as Double)
                        val tempLng = Coordinate.fromDegrees(n.data["lng"] as Double)
                        val tempPoint = Point.at(tempLat, tempLng)
                        EarthCalc.gcdDistance(eventPoint, tempPoint) < n.data.getOrDefault("radius", 50.0) as Double
                    }
                }
                "Person" -> {
                    val personId = event.data["person_id"]
                    notifications.filter { n ->
                        n.data["person_id"] == personId
                    }
                }
                else -> emptyList()
            }.forEach { n ->
                //TODO send post request to update in-active
                RestTemplate().put(
                    "$baseUrl/notification/${n.id}",
                    n.json.toMutableMap().apply {
                        put("is_active", false)
                        put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    }
                )
                userNotificationFluxMap[event.userId]?.second?.next(gson.toJson(n.json))
            }
        }
    }

    init {
        GlobalScope.launch { KafkaStreams(builder.build(), config).start() }
    }

    @GetMapping("notifications/{userId}")
    fun getNotificationFlux(@PathVariable("userId") userId: String): Flux<String> {
        return userNotificationFluxMap.getOrPut(userId) {
            val flux = DirectProcessor.create<String>().serialize()
            Pair(flux, flux.sink())
        }.first
    }

    @GetMapping("events/{userId}")
    fun getEventFlux(@PathVariable("userId") userId: String): Flux<String> {
        return userEventFluxMap.getOrPut(userId) {
            val flux = DirectProcessor.create<String>().serialize()
            Pair(flux, flux.sink())
        }.first
    }
}


fun main(args: Array<String>) {
    runApplication<SuperBrainApplication>(*args) {
        setBannerMode(Banner.Mode.OFF)
    }
}

data class Notification(
    val id: String,
    val data: Map<String, Any>,
    val json: Map<String, Any>
)