package com.gtc.opportunity.trader.service.xoopportunity.creation;

import com.google.common.collect.ImmutableSet;
import com.gtc.opportunity.trader.domain.ClientConfig;
import com.gtc.opportunity.trader.domain.Trade;
import com.gtc.opportunity.trader.domain.TradeStatus;
import com.gtc.opportunity.trader.repository.TradeRepository;
import com.gtc.opportunity.trader.service.xoopportunity.creation.fastexception.Reason;
import com.gtc.opportunity.trader.service.xoopportunity.creation.fastexception.RejectionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Created by Valentyn Berezin on 27.06.18.
 */
@Service
@RequiredArgsConstructor
public class TotalAmountTradeLimiter {

    private final ConfigCache cache;
    private final TradeRepository tradeRepository;

    @Transactional(readOnly = true)
    public boolean canProceed(Trade trade) {
        BigDecimal bal = tradeRepository.tradeBalanceWithSide(trade.getClient(), trade.getCurrencyFrom(),
                trade.getCurrencyTo(), ImmutableSet.of(TradeStatus.UNKNOWN, TradeStatus.OPENED,
                        TradeStatus.CLOSED, TradeStatus.DONE_MAN));
        ClientConfig cfg = cache.getClientCfg(trade.getClient().getName(), trade.getCurrencyFrom(), trade.getCurrencyTo())
                .orElseThrow(() -> new RejectionException(Reason.NO_CONFIG));

        BigDecimal newBal = bal.add(trade.getAmount());

        if (newBal.abs().compareTo(bal.abs()) < 0) {
            return true;
        }

        return cfg.getXoConfig().getSingleSideTradeLimit().compareTo(bal.abs()) >= 0;
    }
}
