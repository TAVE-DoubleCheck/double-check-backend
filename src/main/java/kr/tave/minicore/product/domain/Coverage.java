package kr.tave.minicore.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** 보장 문단 (V1+V3). 문단 1개 = 1행, source_line_no 순으로 보여 준다. */
@Entity
@Table(name = "coverage")
@Immutable
public class Coverage {

    @Id
    private Long id;

    @Column(name = "version_id")
    private long versionId;

    /** 소속 급부명 (각주 문단은 바로 위 급부명을 따른다) */
    @Column(name = "benefit_name")
    private String benefitName;

    @Column(name = "condition_text")
    private String conditionText;

    /** 문단 시작 SNTC_LINE_NO (원본 추적용) */
    @Column(name = "source_line_no")
    private short sourceLineNo;

    /** '*'로 시작하는 부가조건 문단 (예: 납입면제) */
    @Column(name = "is_note")
    private boolean note;

    protected Coverage() {
    }

    public Long getId() {
        return id;
    }

    public long getVersionId() {
        return versionId;
    }

    public String getBenefitName() {
        return benefitName;
    }

    public String getConditionText() {
        return conditionText;
    }

    public short getSourceLineNo() {
        return sourceLineNo;
    }

    public boolean isNote() {
        return note;
    }
}
