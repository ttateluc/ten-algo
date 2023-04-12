package com.gtc.opportunity.trader.service.nnopportunity.solver;

import com.gtc.model.provider.OrderBook;
import com.gtc.opportunity.trader.domain.NnConfig;
import com.gtc.opportunity.trader.service.dto.FlatOrderBookWithHistory;
import com.gtc.opportunity.trader.service.nnopportunity.dto.Snapshot;
import com.gtc.opportunity.trader.service.nnopportunity.repository.NnDataRepository;
import com.gtc.opportunity.trader.service.nnopportunity.repository.Strategy;
import com.gtc.opportunity.trader.service.nnopportunity.repository.StrategyDetails;
import com.gtc.opportunity.trader.service.nnopportunity.solver.model.ModelFactory;
import com.gtc.opportunity.trader.service.nnopportunity.solver.model.NnModelPredict;
import com.gtc.opportunity.trader.service.nnopportunity.solver.time.LocalTime;
import com.gtc.opportunity.trader.service.nnopportunity.solver.time.RateLimiter;
import com.gtc.opportunity.trader.service.nnopportunity.util.OrderBookKey;
import com.gtc.opportunity.trader.service.xoopportunity.creation.ConfigCache;
import com.newrelic.api.agent.Trace;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Created by Valentyn Berezin on 27.07.18.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NnSolver {

    private static final long MILLIS_IN_MINUTE = 60000L;

    private final Map<KeyAndStrategy, NnModelPredict> predictors = new ConcurrentHashMap<>();
    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    private final LocalTime localTime;
    private final ConfigCache cfgCache;
    private final ModelFactory factory;
    private final NnDataRepository repository;

    Optional<StrategyDetails> findStrategy(OrderBook book, Map<Strategy, FlatOrderBookWithHistory> candidates) {
        Map<Double, StrategyDetails> votes = new HashMap<>();

        candidates.forEach((strategy, strategyValue) -> {
            Optional<StrategyDetails> result = solveForStrategy(book, strategyValue, strategy);
            result.ifPresent(it -> votes.put(it.getConfidence(), it));
        });

        return Optional.ofNullable(
                votes.get(votes.keySet().stream().max(Double::compareTo).orElse(0.0))
        );
    }

    @Trace(dispatcher = true)
    @Scheduled(fixedDelayString = "#{${app.nn.schedule.createModelsEachS} * 1000}")
    public void createModels() {
        try {
            Arrays.stream(Strategy.values()).forEach(strategy ->
                    repository.getModellable().stream()
                            .map(it -> repository.getDataToAnalyze(it, strategy))
                            .flatMap(o -> o.map(Stream::of).orElseGet(Stream::empty))
                            .filter(it -> !oldData(it))
                            .forEach(it -> createPredictor(strategy, it))
            );
        } catch (RuntimeException ex) {
            log.error("Failed creating models", ex);
        }
    }

    public ModelStatistics ageStats(long currTime) {
        DescriptiveStatistics statistics = new DescriptiveStatistics();
        predictors.values().stream()
                .map(it -> currTime - it.getCreationTimestamp())
                .map(it -> it / 1000.0)
                .forEach(statistics::addValue);

        return new ModelStatistics(
                statistics.getPercentile(50.0),
                statistics.getPercentile(50.0),
                statistics.getPercentile(75.0)
        );
    }

    private Optional<StrategyDetails> solveForStrategy(
            OrderBook book, FlatOrderBookWithHistory flatBook, Strategy strategy) {
        NnModelPredict predict = predictors.get(key(OrderBookKey.key(book), strategy));

        if (null == predict) {
            return Optional.empty();
        }

        if (oldModel(book, predict) || !getLimiter(book).tryAcquire()) {
            return Optional.empty();
        }

        return predict.computeStrategyIfPossible(strategy, flatBook);
    }

    private void createPredictor(Strategy strategy, Snapshot snapshot) {
        String oldName = Thread.currentThread().getName();
        Thread.currentThread().setName(String.format(
                "Build model %s-%s->%s",
                snapshot.getKey().getClient(),
                snapshot.getKey().getPair().getFrom().getCode(),
                snapshot.getKey().getPair().getTo().getCode()));

        log.info("Create(train) predictor for {}", snapshot.getKey());
        try {
            NnModelPredict predict = factory.buildModel(snapshot);
            predictors.put(key(snapshot.getKey(), strategy), predict);
        } catch (NnModelPredict.TrainingFailed ex) {
            log.warn("Training failed for {}", snapshot.getKey());
        } catch (Exception ex) {
            log.warn("Unknown exception {}", ex.getMessage(), ex);
        } finally {
            Thread.currentThread().setName(oldName);
        }
    }

    private boolean oldModel(OrderBook book, NnModelPredict predict) {
        return book.getMeta().getTimestamp() - predict.getCreationTimestamp()
                >= cfgCache.requireConfig(book).getOldThresholdM() * MILLIS_IN_MINUTE;
    }

    private boolean oldData(Snapshot snapshot) {
        long actOld = (long) snapshot.getProceedLabel().stream()
                .mapToLong(FlatOrderBookWithHistory::getTimestamp).average()
                .orElse(0.0);
        long noopOld = (long) snapshot.getNoopLabel().stream()
                .mapToLong(FlatOrderBookWithHistory::getTimestamp).average()
                .orElse(0.0);

        return localTime.timestampMs() - Math.min(actOld, noopOld)
                >= cfgCache.requireConfig(snapshot).getOldThresholdM() * MILLIS_IN_MINUTE;
    }

    private RateLimiter getLimiter(OrderBook book) {
        NnConfig config = cfgCache.requireConfig(book);
        return limiters.computeIfAbsent(
                limiterKey(book),
                id -> new RateLimiter(
                        BigDecimal.ONE.divide(config.getBookTestForOpenPerS(), MathContext.DECIMAL128)
                                .movePointRight(3).longValue(),
                        localTime)
        );
    }

    private static String limiterKey(OrderBook book) {
        return book.getMeta().getClient()
                + book.getMeta().getPair().getFrom().getCode()
                + book.getMeta().getPair().getTo().getCode();
    }

    private static KeyAndStrategy key(Key key, Strategy strategy) {
        return new KeyAndStrategy(key, strategy);
    }

    @Data
    public static class ModelStatistics {

        private final double agePercentile50;
        private final double agePercentile75;
        private final double agePercentile90;
    }

    @Data
    @EqualsAndHashCode
    private static class KeyAndStrategy {

        private final Key key;
        private final Strategy strategy;
    }
}
