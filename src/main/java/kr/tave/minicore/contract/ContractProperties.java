package kr.tave.minicore.contract;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 contract.*
 *
 * @param firstPremiumWaitDays 1회차 보험료를 기다리는 기간(일). 신청일 + N일 = 1회차 청구서 마감일
 */
@ConfigurationProperties(prefix = "contract")
public record ContractProperties(int firstPremiumWaitDays) {
}
