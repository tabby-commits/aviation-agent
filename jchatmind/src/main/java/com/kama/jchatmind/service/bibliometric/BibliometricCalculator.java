package com.kama.jchatmind.service.bibliometric;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文献计量纯函数计算器（中期报告 3.2.3 节公式，无副作用、无 IO）
 *
 * 口径约定：
 * - 国家归属：论文的"参与国集合"由 affiliations 地址解析得到；firstAuthorCountry 仅为兜底（地址缺失时整篇归属第一作者国别）
 * - 分数计数：N_W = Σ n_ic/n_i（n_i=该论文机构总数，n_ic=其中属 c 国的数量）
 * - 高被引参照组：同输入集合内同发表年份（+同文献类型）的论文；阈值=组内被引前 10%，阈值处并列按剩余名额均分（Waltman & Schreiber 分数分配）
 * - 机构份额：S_j,c = Σ_{i: j∈A_ic} (1/m_ic) / N_F(c)（m_ic=论文 i 中 c 国机构数）
 */
public final class BibliometricCalculator {

    /** 高被引阈值百分比 */
    public static final double TOP_PERCENT = 0.10;

    /** 解析出的机构地址国家关键词（小写匹配） */
    private static final Set<String> CN_KEYWORDS = Set.of(
            "china", "prc", "beijing", "shanghai", "nanjing", "xian", "xi'an", "chengdu",
            "wuhan", "hangzhou", "hefei", "changsha", "harbin", "guangzhou", "shenzhen",
            "北京", "上海", "南京", "武汉", "长沙", "哈尔滨", "成都", "西安", "合肥", "杭州", "广州", "深圳");
    private static final Set<String> US_KEYWORDS = Set.of("united states", "usa", "u.s.a", "u. s. a");

    private BibliometricCalculator() {
    }

    /** 机构地址 → 国家代码（CN/US/其他返回 null） */
    public static String parseCountry(String affiliation) {
        if (affiliation == null || affiliation.isBlank()) {
            return null;
        }
        String lower = affiliation.toLowerCase();
        // 地址通常以国家结尾，关键词按子串匹配（"united states" 需整词）
        if (lower.contains("united states") || lower.matches(".*\\busa\\b.*") || lower.contains("u.s.a")) {
            return "US";
        }
        for (String keyword : CN_KEYWORDS) {
            if (lower.contains(keyword)) {
                return "CN";
            }
        }
        return null;
    }

    /** 机构显示名：地址首段（逗号前） */
    public static String institutionName(String affiliation) {
        if (affiliation == null || affiliation.isBlank()) {
            return "(未知机构)";
        }
        String first = affiliation.split(",")[0].trim();
        return first.isEmpty() ? affiliation.trim() : first;
    }

    public static BibliometricResult calculate(List<PaperBiblioRecord> papers, int yearFrom, int yearTo) {
        List<PaperBiblioRecord> scoped = papers.stream()
                .filter(p -> p.publishYear() != null && p.publishYear() >= yearFrom && p.publishYear() <= yearTo)
                .toList();

        // 参与国集合与机构-国家映射（每篇预处理）
        Map<String, Set<String>> countriesByDoc = new LinkedHashMap<>();
        Map<String, List<InstitutionCountry>> institutionsByDoc = new LinkedHashMap<>();
        for (PaperBiblioRecord p : scoped) {
            Set<String> countries = new java.util.LinkedHashSet<>();
            List<InstitutionCountry> institutions = new ArrayList<>();
            List<String> affs = p.affiliations() == null ? List.of() : p.affiliations();
            for (String aff : affs) {
                String country = parseCountry(aff);
                institutions.add(new InstitutionCountry(institutionName(aff), country));
                if (country != null) {
                    countries.add(country);
                }
            }
            if (countries.isEmpty() && p.firstAuthorCountry() != null) {
                countries.add(p.firstAuthorCountry()); // 地址无法解析时兜底
            }
            countriesByDoc.put(p.docId(), countries);
            institutionsByDoc.put(p.docId(), institutions);
        }

        // 高被引 h_i（按年份参照组，含并列分数分配）
        Map<String, Double> hByDoc = computeHighCitedWeights(scoped);

        // 收集全部参与国
        Set<String> allCountries = new java.util.TreeSet<>();
        countriesByDoc.values().forEach(allCountries::addAll);

        Map<String, BibliometricResult.CountryStats> byCountry = new LinkedHashMap<>();
        for (String country : allCountries) {
            byCountry.put(country, computeCountry(country, scoped, countriesByDoc, institutionsByDoc, hByDoc));
        }

        return BibliometricResult.builder()
                .totalPapers(scoped.size())
                .yearFrom(yearFrom)
                .yearTo(yearTo)
                .byCountry(byCountry)
                .build();
    }

    private record InstitutionCountry(String institution, String country) {
    }

    private static BibliometricResult.CountryStats computeCountry(String country,
                                                                  List<PaperBiblioRecord> scoped,
                                                                  Map<String, Set<String>> countriesByDoc,
                                                                  Map<String, List<InstitutionCountry>> institutionsByDoc,
                                                                  Map<String, Double> hByDoc) {
        long fullCount = 0;
        double fractionalCount = 0;
        long internationalCount = 0;
        double highCited = 0;
        Map<Integer, Long> yearSeries = new LinkedHashMap<>();
        Map<String, Double> shareNumeratorByInstitution = new LinkedHashMap<>(); // Σ 1/m_ic
        Map<String, Long> paperCountByInstitution = new LinkedHashMap<>();

        for (PaperBiblioRecord p : scoped) {
            Set<String> countries = countriesByDoc.get(p.docId());
            if (!countries.contains(country)) {
                continue;
            }
            fullCount++;
            yearSeries.merge(p.publishYear(), 1L, Long::sum);

            List<InstitutionCountry> institutions = institutionsByDoc.get(p.docId());
            int totalInstitutions = institutions.size();
            long countryInstitutions = institutions.stream().filter(i -> country.equals(i.country())).count();
            if (totalInstitutions > 0) {
                fractionalCount += (double) countryInstitutions / totalInstitutions;
            }

            if (countries.size() >= 2) {
                internationalCount++;
            }

            Double h = hByDoc.get(p.docId());
            if (h != null) {
                double weight = totalInstitutions > 0 ? (double) countryInstitutions / totalInstitutions : 1.0;
                highCited += weight * h;
            }

            if (countryInstitutions > 0) {
                double perInstitution = 1.0 / countryInstitutions;
                for (InstitutionCountry ic : institutions) {
                    if (country.equals(ic.country())) {
                        shareNumeratorByInstitution.merge(ic.institution(), perInstitution, Double::sum);
                        paperCountByInstitution.merge(ic.institution(), 1L, Long::sum);
                    }
                }
            }
        }

        // 三年后向移动平均
        Map<Integer, Double> movingAvg = new LinkedHashMap<>();
        List<Integer> years = yearSeries.keySet().stream().sorted().toList();
        for (int i = 2; i < years.size(); i++) {
            int y = years.get(i);
            double avg = (yearSeries.get(years.get(i - 2)) + yearSeries.get(years.get(i - 1)) + yearSeries.get(y)) / 3.0;
            movingAvg.put(y, avg);
        }

        // CAGR（首末年论文数为正）
        Double cagr = null;
        if (!years.isEmpty()) {
            long first = yearSeries.get(years.get(0));
            long last = yearSeries.get(years.get(years.size() - 1));
            int span = years.get(years.size() - 1) - years.get(0);
            if (first > 0 && last > 0 && span > 0) {
                cagr = (Math.pow((double) last / first, 1.0 / span) - 1) * 100;
            }
        }

        // 机构份额与 HHI（fullCount 需 final 拷贝供 lambda 捕获）
        final long fc = fullCount;
        List<BibliometricResult.InstitutionShare> shares = shareNumeratorByInstitution.entrySet().stream()
                .map(e -> BibliometricResult.InstitutionShare.builder()
                        .institution(e.getKey())
                        .share(fc > 0 ? e.getValue() / fc : 0)
                        .paperCount(paperCountByInstitution.getOrDefault(e.getKey(), 0L))
                        .build())
                .sorted(Comparator.comparingDouble(BibliometricResult.InstitutionShare::getShare).reversed())
                .toList();
        double hhi = shares.stream().mapToDouble(s -> s.getShare() * s.getShare()).sum();

        return BibliometricResult.CountryStats.builder()
                .country(country)
                .fullCount(fullCount)
                .fractionalCount(round(fractionalCount))
                .internationalCount(internationalCount)
                .internationalRatio(fullCount > 0 ? round(internationalCount * 100.0 / fullCount) : 0)
                .highCitedCount(round(highCited))
                .highCitedRatio(fractionalCount > 0 ? round(highCited * 100.0 / fractionalCount) : 0)
                .yearSeries(yearSeries)
                .movingAverage3(movingAvg)
                .cagr(cagr == null ? null : round(cagr))
                .institutionShares(shares)
                .institutionHhi(round(hhi))
                .effectiveInstitutions(hhi > 0 ? round(1.0 / hhi) : 0)
                .build();
    }

    /**
     * 高被引权重：参照组=同年份同文献类型，被引降序前 10%（k=max(1, floor(n*10%))，n≥10 时纯 floor）；
     * 阈值处并列按剩余名额均分（分数分配）。
     */
    private static Map<String, Double> computeHighCitedWeights(List<PaperBiblioRecord> scoped) {
        Map<String, Double> hByDoc = new LinkedHashMap<>();
        // 参照组键：年份|文献类型（类型为空并入同组）
        Map<String, List<PaperBiblioRecord>> groups = new LinkedHashMap<>();
        for (PaperBiblioRecord p : scoped) {
            if (p.citedCount() == null) {
                continue;
            }
            String key = p.publishYear() + "|" + (p.docType() == null ? "" : p.docType());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        for (List<PaperBiblioRecord> group : groups.values()) {
            group.sort(Comparator.comparingInt(PaperBiblioRecord::citedCount).reversed());
            int n = group.size();
            if (n < 10) {
                continue; // 样本不足不产出高被引判定（k=floor(n*0.1)=0）
            }
            int k = (int) Math.floor(n * TOP_PERCENT);
            if (k < 1) {
                continue;
            }
            // 前 k 名默认权重 1；阈值（第 k 名的被引值）处并列块内重新分配
            int threshold = group.get(k - 1).citedCount();
            int guaranteed = 0;
            for (PaperBiblioRecord p : group) {
                if (p.citedCount() > threshold) {
                    guaranteed++;
                }
            }
            // 并列块（cited == threshold）成员均分剩余配额 k-guaranteed
            List<PaperBiblioRecord> tied = group.stream()
                    .filter(p -> p.citedCount() == threshold).toList();
            double tiedWeight = tied.isEmpty() ? 1.0 : (double) (k - guaranteed) / tied.size();

            for (int i = 0; i < group.size(); i++) {
                PaperBiblioRecord p = group.get(i);
                double h;
                if (p.citedCount() > threshold) {
                    h = 1.0;
                } else if (p.citedCount() == threshold) {
                    h = tiedWeight;
                } else {
                    h = 0.0;
                }
                if (h > 0) {
                    hByDoc.put(p.docId(), h);
                }
            }
        }
        return hByDoc;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
