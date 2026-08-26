package com.kama.jchatmind.service.bibliometric;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 文献计量结果（对应中期报告 3.2.3 节指标（9）—（12）（16）公式）
 */
@Data
@Builder
public class BibliometricResult {

    private int totalPapers;

    private int yearFrom;

    private int yearTo;

    /** 分国统计（key=国家代码，如 CN/US） */
    private Map<String, CountryStats> byCountry;

    @Data
    @Builder
    public static class CountryStats {
        private String country;

        /** 指标(9) 完全计数 N_F：该国参与的论文数 */
        private long fullCount;

        /** 指标(9) 分数计数 N_W：Σ n_ic/n_i */
        private double fractionalCount;

        /** 指标(12) 国际合作论文数（该国参与的论文中地址含≥2国） */
        private long internationalCount;

        /** 指标(12) 国际合作论文比例 ICR = N_IC/N_F（百分比） */
        private double internationalRatio;

        /** 指标(10) 高被引论文数量 HC（Σ w_ic·h_i，前 10% 含并列分数分配） */
        private double highCitedCount;

        /** 指标(10) 高被引论文占比 HCR = HC/N_W（百分比） */
        private double highCitedRatio;

        /** 指标(11) 年度完全计数序列（年份→论文数） */
        private Map<Integer, Long> yearSeries;

        /** 指标(11) 三年后向移动平均（自第 3 个连续年度起） */
        private Map<Integer, Double> movingAverage3;

        /** 指标(11) 复合年均增长率 CAGR（百分比；首末任一为 0 时为 null） */
        private Double cagr;

        /** 指标(16) 机构贡献份额（降序） */
        private List<InstitutionShare> institutionShares;

        /** 指标(16) 机构集中度 HHI = Σ S_j² */
        private double institutionHhi;

        /** 指标(16) 有效机构数 1/HHI */
        private double effectiveInstitutions;
    }

    @Data
    @Builder
    public static class InstitutionShare {
        private String institution;
        /** S_j,c = Σ(1/m_ic)/N_F */
        private double share;
        /** 该机构参与论文数（完全计数） */
        private long paperCount;
    }
}
