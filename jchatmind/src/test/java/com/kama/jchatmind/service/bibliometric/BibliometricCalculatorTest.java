package com.kama.jchatmind.service.bibliometric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 文献计量计算器单元测试（纯函数，手算已知答案验证公式）
 */
public class BibliometricCalculatorTest {

    private static PaperBiblioRecord paper(String docId, int year, int cited,
                                           String country, String... affiliations) {
        return new PaperBiblioRecord(docId, year, "Article", cited, country, List.of(affiliations));
    }

    /**
     * 8 篇基础数据集（2020-2022）：
     * CN 参与：P1,P2,P3,P5,P7；US 参与：P3,P4,P6,P8
     */
    private List<PaperBiblioRecord> baseSet() {
        return List.of(
                paper("P1", 2020, 50, "CN", "Univ A, Beijing, China", "Univ B, Wuhan, China"),
                paper("P2", 2020, 30, "CN", "Univ A, Beijing, China"),
                paper("P3", 2021, 40, "CN", "Univ C, Shanghai, China", "MIT, Cambridge, United States"),
                paper("P4", 2021, 20, "US", "MIT, Cambridge, United States"),
                paper("P5", 2022, 10, "CN", "Univ B, Wuhan, China", "Univ C, Shanghai, China"),
                paper("P6", 2022, 35, "US", "Caltech, Pasadena, United States", "MIT, United States"),
                paper("P7", 2022, 25, "CN", "Univ A, Beijing, China"),
                paper("P8", 2022, 15, "US", "MIT, United States"));
    }

    @Test
    public void countryCountsAndInternationalRatio() {
        BibliometricResult result = BibliometricCalculator.calculate(baseSet(), 2020, 2022);

        BibliometricResult.CountryStats cn = result.getByCountry().get("CN");
        assertEquals(5, cn.getFullCount(), "CN 完全计数");
        assertEquals(4.5, cn.getFractionalCount(), "CN 分数计数：P1:1 + P2:1 + P3:0.5 + P5:1 + P7:1");
        assertEquals(1, cn.getInternationalCount(), "仅 P3 为国际合作（地址含 CN+US）");
        assertEquals(20.0, cn.getInternationalRatio(), "ICR = 1/5");

        BibliometricResult.CountryStats us = result.getByCountry().get("US");
        assertEquals(4, us.getFullCount(), "US 参与含合作论文 P3");
        assertEquals(3.5, us.getFractionalCount(), "US 分数计数：P3:0.5 + P4:1 + P6:1 + P8:1");
        assertEquals(25.0, us.getInternationalRatio(), "ICR = 1/4");
    }

    @Test
    public void yearSeriesMovingAverageAndCagr() {
        BibliometricResult.CountryStats cn =
                BibliometricCalculator.calculate(baseSet(), 2020, 2022).getByCountry().get("CN");

        assertEquals(2L, cn.getYearSeries().get(2020));
        assertEquals(1L, cn.getYearSeries().get(2021));
        assertEquals(2L, cn.getYearSeries().get(2022));

        // 三年后向移动平均仅 2022 年有值：(2+1+2)/3
        assertEquals(1.6667, cn.getMovingAverage3().get(2022), 0.0001);

        // CAGR：2020→2022 论文数 2→2，span=2 → 0%
        assertEquals(0.0, cn.getCagr(), 0.0001);
    }

    @Test
    public void cagrShouldBeNullWhenSingleYear() {
        BibliometricResult.CountryStats us =
                BibliometricCalculator.calculate(baseSet(), 2021, 2021).getByCountry().get("US");
        assertNull(us.getCagr(), "单年区间 CAGR 无定义");
    }

    @Test
    public void institutionSharesAndHhi() {
        BibliometricResult.CountryStats cn =
                BibliometricCalculator.calculate(baseSet(), 2020, 2022).getByCountry().get("CN");

        // UnivA: P1(1/2)+P2(1)+P7(1)=2.5 → 0.5；UnivB: P1(1/2)+P5(1/2)=1 → 0.2；UnivC: P3(1)+P5(1/2)=1.5 → 0.3
        var shares = cn.getInstitutionShares();
        assertEquals("Univ A", shares.get(0).getInstitution());
        assertEquals(0.5, shares.get(0).getShare(), 0.0001);
        assertEquals(3, shares.size());

        // HHI = 0.5²+0.2²+0.3² = 0.38；有效机构数 = 1/0.38 ≈ 2.6316
        assertEquals(0.38, cn.getInstitutionHhi(), 0.0001);
        assertEquals(2.6316, cn.getEffectiveInstitutions(), 0.001);
    }

    @Test
    public void highCitedWithTiedThresholdShouldSplitFractionally() {
        // 12 篇同组（2020 Article）：k=floor(1.2)=1，两个 cited=100 并列于阈值 → 各 0.5
        List<PaperBiblioRecord> papers = new java.util.ArrayList<>(List.of(
                paper("H1", 2020, 100, "CN", "Univ A, Beijing, China"),
                paper("H2", 2020, 100, "CN", "Univ A, Beijing, China")));
        int[] cited = {80, 70, 60, 50, 40, 30, 20, 15, 10, 5};
        for (int i = 0; i < cited.length; i++) {
            papers.add(paper("H" + (i + 3), 2020, cited[i], "US", "MIT, United States"));
        }

        BibliometricResult.CountryStats cn =
                BibliometricCalculator.calculate(papers, 2020, 2020).getByCountry().get("CN");

        // HC(CN) = 0.5 + 0.5 = 1.0
        assertEquals(1.0, cn.getHighCitedCount(), 0.0001);
        // HCR = HC/N_W = 1.0/2 = 50%
        assertEquals(50.0, cn.getHighCitedRatio(), 0.0001);
    }

    @Test
    public void smallGroupShouldNotProduceHighCited() {
        // 组内 n<10：k=0，不产出高被引判定
        BibliometricResult.CountryStats cn =
                BibliometricCalculator.calculate(baseSet(), 2020, 2022).getByCountry().get("CN");
        assertEquals(0.0, cn.getHighCitedCount());
    }

    @Test
    public void yearScopeShouldFilterRecords() {
        BibliometricResult result = BibliometricCalculator.calculate(baseSet(), 2022, 2022);
        assertEquals(4, result.getTotalPapers(), "2022 年仅 P5/P6/P7/P8");
    }

    @Test
    public void countryParsingShouldHandleChineseAddresses() {
        assertEquals("CN", BibliometricCalculator.parseCountry("北京邮电大学信息与通信工程学院"));
        assertEquals("US", BibliometricCalculator.parseCountry("Missouri University of Science and Technology, USA"));
        assertEquals("CN", BibliometricCalculator.parseCountry("Inst A, Wuhan, China"));
        assertNull(BibliometricCalculator.parseCountry("ETH Zurich, Switzerland"));
    }
}
