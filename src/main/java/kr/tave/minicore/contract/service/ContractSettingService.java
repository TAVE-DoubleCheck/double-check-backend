package kr.tave.minicore.contract.service;

import kr.tave.minicore.auth.security.LoginUser;
import kr.tave.minicore.common.error.ApiException;
import kr.tave.minicore.common.error.ErrorCode;
import kr.tave.minicore.contract.domain.Contract;
import kr.tave.minicore.contract.repository.ContractRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 고객이 바꾸는 계약 설정. 지금은 자동이체 켜기/끄기 */
@Service
public class ContractSettingService {

    private final ContractRepository contracts;

    public ContractSettingService(ContractRepository contracts) {
        this.contracts = contracts;
    }

    /**
     * 자동이체 켜기/끄기. 본인 계약만 (남의 계약은 있어도 404).
     * 켜 두면 매일 작업(AUTO_DEBIT)이 납입기일에 자동으로 납부한다. 이미 미납인 회차도 유예 마감 전이면 다음 날 함께 낸다.
     */
    @Transactional
    public Contract changeAutoDebit(LoginUser me, long contractId, boolean enabled) {
        Contract c = contracts.findById(contractId)
                .filter(x -> Objects.equals(x.getUserId(), me.id()))
                .orElseThrow(() -> new ApiException(ErrorCode.CONTRACT_NOT_FOUND, "계약 없음: " + contractId));
        c.changeAutoDebit(enabled);
        return c;
    }
}
