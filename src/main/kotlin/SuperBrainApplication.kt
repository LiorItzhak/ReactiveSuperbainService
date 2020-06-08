package com.superbrain

import com.google.gson.Gson
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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
import reactor.core.publisher.DirectProcessor
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxProcessor
import reactor.core.publisher.FluxSink
import java.util.*


@SpringBootApplication
@RestController
@RequestMapping("/")
open class SuperBrainApplication {

    val gson = Gson()
    val config = Properties().apply {
        put(StreamsConfig.APPLICATION_ID_CONFIG, "application189")
        put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
        put(StreamsConfig.STATE_DIR_CONFIG, "C:\\tmp"); // on Windows
//        put(StreamsConfig.STATE_DIR_CONFIG , "/tmp"); // on Linux
        put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().javaClass)  //string casting
        put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().javaClass)  //string casting
    }

    val fluxMap = mutableMapOf<String, Pair<FluxProcessor<String, String>, FluxSink<String>>>()
    val builder = StreamsBuilder().apply {
        stream<String, String>("messege").foreach { key, value ->
            @Suppress("UNCHECKED_CAST") val obj = gson.fromJson(value, Map::class.java) as Map<String, Any>
            println(value)
            fluxMap[obj["user_id"]]?.second?.next(value)
        }
    }

    init {
        GlobalScope.launch { KafkaStreams(builder.build(), config).start() }
    }

    @GetMapping("/{userId}")
    fun getFlux(@PathVariable("userId") userId: String): Flux<String> {
        return fluxMap.getOrPut(userId) {
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