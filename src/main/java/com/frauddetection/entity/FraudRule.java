package com.frauddetection.entity;

import com.frauddetection.enums.RuleType;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "fraud_rules")
public class FraudRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private RuleType ruleType;

    @Column(nullable = false)
    private boolean enabled = true;

    // Generic threshold fields reused across rule types
    private BigDecimal thresholdAmount;   // for HIGH_VALUE
    private Integer thresholdCount;       // for FREQUENCY / REPEATED_FAILURE
    private Integer windowMinutes;        // time window for FREQUENCY / GEO_ANOMALY

    private String description;

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public RuleType getRuleType() { return ruleType; }
    public void setRuleType(RuleType ruleType) { this.ruleType = ruleType; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public BigDecimal getThresholdAmount() { return thresholdAmount; }
    public void setThresholdAmount(BigDecimal thresholdAmount) { this.thresholdAmount = thresholdAmount; }

    public Integer getThresholdCount() { return thresholdCount; }
    public void setThresholdCount(Integer thresholdCount) { this.thresholdCount = thresholdCount; }

    public Integer getWindowMinutes() { return windowMinutes; }
    public void setWindowMinutes(Integer windowMinutes) { this.windowMinutes = windowMinutes; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}