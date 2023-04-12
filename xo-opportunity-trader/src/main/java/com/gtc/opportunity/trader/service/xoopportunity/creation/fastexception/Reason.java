package com.gtc.opportunity.trader.service.xoopportunity.creation.fastexception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Created by Valentyn Berezin on 01.04.18.
 */
@Getter
@RequiredArgsConstructor
public enum Reason {
    GEN_ERR("Uncategorized exception"),
    NO_CONFIG("Missing config"),
    DISABLED("Trade disabled"),
    LOW_PROFIT_PRE("Low profit (pre)"),
    RATE_TOO_HIGH("Opportunity creation rate limit"),
    LOW_PROFIT("Low profit"),
    LOW_BALANCE("Low balance"),
    MAX_LT_MIN("Calculated capacity max < min"),
    VALIDATION_FAIL("Failed DTO validation"),
    OPT_CONSTR_FAIL("Optimization failed hard constraint"),
    TOO_FREQUENT_SOLVE("Too frequent solver invocation"),
    SOL_VALID_FAIL("Optimization solution failed validation"),
    LOW_BAL("Low wallet balance"),
    SIDE_LIMIT("Limited due to high trade sum on one side");

    private final String msg;
}
