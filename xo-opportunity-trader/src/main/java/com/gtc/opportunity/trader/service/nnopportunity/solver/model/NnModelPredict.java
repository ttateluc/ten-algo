package com.gtc.opportunity.trader.service.nnopportunity.solver.model;

import com.gtc.opportunity.trader.domain.NnConfig;
import com.gtc.opportunity.trader.service.dto.FlatOrderBookWithHistory;
import com.gtc.opportunity.trader.service.nnopportunity.dto.Snapshot;
import com.gtc.opportunity.trader.service.nnopportunity.repository.Strategy;
import com.gtc.opportunity.trader.service.nnopportunity.repository.StrategyDetails;
import com.gtc.opportunity.trader.service.nnopportunity.solver.time.LocalTime;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;

import java.util.List;
import java.util.Optional;

import static com.gtc.opportunity.trader.service.nnopportunity.solver.model.FeatureMapper.CAN_PROCEED_POS;
import static com.gtc.opportunity.trader.service.nnopportunity.solver.model.FeatureMapper.LABEL_SIZE;

/**
 * Created by Valentyn Berezin on 29.07.18.
 */
@Slf4j
public class NnModelPredict {

    @Getter
    private final long creationTimestamp;

    private final LocalTime localTime;
    private final FeatureMapper featureMapper;
    private final NnConfig nnConfig;
    private final MultiLayerNetwork model;
    private final int avgNoopLabelAgeS;
    private final int avgActLabelAgeS;

    NnModelPredict(LocalTime localTime, NnConfig config, Snapshot snapshot, FeatureMapper mapper) throws TrainingFailed {
        this.localTime = localTime;
        this.nnConfig = config;
        this.model = new MultiLayerNetwork(buildModelConfig(config));
        this.model.init();
        this.featureMapper = mapper;
        this.creationTimestamp = localTime.timestampMs();
        Splitter split = new Splitter(config, snapshot);
        trainModel(split);
        assesModel(split);
        long timestamp = localTime.timestampMs();
        avgNoopLabelAgeS = (int) (split.getNoopTrain().stream()
                .mapToDouble(it -> timestamp - it.getTimestamp()).average().orElse(0.0) / 1000.0);
        avgActLabelAgeS = (int) (split.getProceedTrain().stream()
                .mapToDouble(it -> timestamp - it.getTimestamp()).average().orElse(0.0) / 1000.0);
    }

    public Optional<StrategyDetails> computeStrategyIfPossible(Strategy strategy, FlatOrderBookWithHistory book) {
        INDArray results = model.output(featureMapper.extractFeatures(book));
        float voteValue = results.getFloat(CAN_PROCEED_POS);

        if (voteValue <= nnConfig.getTruthThreshold().doubleValue()) {
            return Optional.empty();
        }

        return Optional.of(new StrategyDetails(
                strategy,
                voteValue,
                (int) (localTime.timestampMs() - creationTimestamp) / 1000,
                avgNoopLabelAgeS,
                avgActLabelAgeS
        ));
    }

    @SneakyThrows
    private void trainModel(Splitter splitter) {
        log.info("Training model");
        DataSet trainData = featureMapper.extract(splitter.getProceedTrain(), splitter.getNoopTrain());
        for (int i = 0; i < nnConfig.getNTrainIterations(); ++i) {
            model.fit(trainData);
        }
        log.info("Done training model");
    }

    private void assesModel(Splitter splitter) throws TrainingFailed {
        Evaluation eval = new Evaluation(LABEL_SIZE);
        asses(eval, splitter.getNoopTest(), false);
        asses(eval, splitter.getProceedTest(), true);

        if (eval.falsePositiveRate(CAN_PROCEED_POS) > nnConfig.getProceedFalsePositive().doubleValue()) {
            log.info("Model failed assessment {} > {}",
                    eval.falsePositiveRate(CAN_PROCEED_POS),
                    nnConfig.getProceedFalsePositive());
            throw new TrainingFailed();
        }
        log.info("Model passed assessment");
    }

    private void asses(Evaluation eval, List<FlatOrderBookWithHistory> books, boolean isProceed) {
        books.stream().map(featureMapper::extractFeatures).forEach(features ->
                eval.eval(model.output(features), featureMapper.extractLabels(isProceed))
        );
    }

    private static MultiLayerConfiguration buildModelConfig(NnConfig cfg) {
        return MultiLayerConfiguration.fromYaml(cfg.getNetworkYamlSpec());
    }

    @Getter
    private static class Splitter {

        private final List<FlatOrderBookWithHistory> proceedTrain;
        private final List<FlatOrderBookWithHistory> noopTrain;
        private final List<FlatOrderBookWithHistory> proceedTest;
        private final List<FlatOrderBookWithHistory> noopTest;

        Splitter(NnConfig config, Snapshot snapshot) {
            int splitTrainProceed = (int) (config.getTrainRelativeSize().doubleValue()
                    * snapshot.getProceedLabel().size());
            int splitTrainNoop = (int) (config.getTrainRelativeSize().doubleValue() * snapshot.getNoopLabel().size());
            proceedTrain = snapshot.getProceedLabel().subList(0, splitTrainProceed);
            noopTrain = snapshot.getNoopLabel().subList(0, splitTrainNoop);
            proceedTest = snapshot.getProceedLabel().subList(splitTrainProceed, snapshot.getProceedLabel().size());
            noopTest = snapshot.getNoopLabel().subList(splitTrainNoop, snapshot.getNoopLabel().size());
        }
    }

    public static class TrainingFailed extends Exception {
    }
}
