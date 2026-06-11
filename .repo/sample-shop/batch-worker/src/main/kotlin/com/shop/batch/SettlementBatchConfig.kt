package com.shop.batch

import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemReader
import org.springframework.batch.item.ItemWriter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableBatchProcessing
class SettlementBatchConfig {

    @Bean
    fun settlementJob(settlementStep: Step): Job {
        // Job -> Step structural wiring
        return JobBuilder("settlementJob")
            .start(settlementStep)
            .build()
    }

    @Bean
    fun settlementStep(
        settlementReader: ItemReader<Long>,
        settlementProcessor: ItemProcessor<Long, Long>,
        settlementWriter: ItemWriter<Long>,
    ): Step {
        // Step -> reader/processor/writer structural wiring
        return StepBuilder("settlementStep")
            .reader(settlementReader)
            .processor(settlementProcessor)
            .writer(settlementWriter)
            .build()
    }

    @Bean
    fun settlementReader(): ItemReader<Long> {
        return ItemReader { 1L }
    }

    @Bean
    fun settlementProcessor(): ItemProcessor<Long, Long> {
        return ItemProcessor { it * 2 }
    }

    @Bean
    fun settlementWriter(): ItemWriter<Long> {
        return ItemWriter { items -> println(items) }
    }
}
