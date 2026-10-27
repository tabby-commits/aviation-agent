package com.kama.jchatmind.service.bibliometric;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    /** WoS C1 首段中可视为机构主体的标记；缺少主体的段通常是被分号误拆的地址续段。 */
    private static final Pattern INSTITUTION_MARKER = Pattern.compile(
            "(?i).*(\\buniv(?:ersity)?\\b|\\bcollege\\b|\\bacad(?:emy)?\\b|\\binst(?:itute)?\\b|"
                    + "\\blab(?:oratory)?\\b|\\bcentre\\b|\\bcenter\\b|\\bcorp(?:oration)?\\b|"
                    + "\\bltd\\b|\\binc\\b|\\bcompany\\b|\\bnasa\\b|\\bcaltech\\b|\\bmit\\b|"
                    + "\\bhuawei\\b|\\bspacex\\b|大学|学院|研究院|实验室|公司|中心).*");

    /** 仅用于识别国际合作伙伴；结果主体仍只输出 CN/US。 */
    private static final Map<String, Set<String>> PARTNER_KEYWORDS = Map.ofEntries(
            Map.entry("CA", Set.of("canada")),
            Map.entry("KR", Set.of("south korea", "republic of korea")),
            Map.entry("JP", Set.of("japan")),
            Map.entry("GB", Set.of("united kingdom", "england", "scotland", "wales")),
            Map.entry("DE", Set.of("germany")),
            Map.entry("FR", Set.of("france")),
            Map.entry("IT", Set.of("italy")),
            Map.entry("ES", Set.of("spain")),
            Map.entry("AU", Set.of("australia")),
            Map.entry("NZ", Set.of("new zealand")),
            Map.entry("SG", Set.of("singapore")),
            Map.entry("IN", Set.of("india")),
            Map.entry("CH", Set.of("switzerland")),
            Map.entry("NL", Set.of("netherlands")),
            Map.entry("SE", Set.of("sweden")),
            Map.entry("FI", Set.of("finland")),
            Map.entry("NO", Set.of("norway")),
            Map.entry("DK", Set.of("denmark")),
            Map.entry("BE", Set.of("belgium")),
            Map.entry("AT", Set.of("austria")),
            Map.entry("PL", Set.of("poland")),
            Map.entry("PT", Set.of("portugal")),
            Map.entry("GR", Set.of("greece")),
            Map.entry("IE", Set.of("ireland")),
            Map.entry("IL", Set.of("israel")),
            Map.entry("RU", Set.of("russia", "russian federation")),
            Map.entry("UA", Set.of("ukraine")),
            Map.entry("BR", Set.of("brazil")),
            Map.entry("MX", Set.of("mexico")),
            Map.entry("ZA", Set.of("south africa")),
            Map.entry("AE", Set.of("united arab emirates", "uae")),
            Map.entry("SA", Set.of("saudi arabia")),
            Map.entry("TR", Set.of("turkey", "türkiye")),
            Map.entry("TW", Set.of("taiwan")),
            Map.entry("HK", Set.of("hong kong"))
    );

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
        for (Map.Entry<String, Set<String>> entry : PARTNER_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword)) {
                    return entry.getKey();
                }
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

    /**
     * WoS C1 中少量机构地址会在机构名内部被分号切开，例如
     * "Chinese Acad Sci, ... Space Laser; Commun & Testing Technol, ..., Peoples R China"。
     * 导入后后半段会被误认成独立机构。这里将没有机构主体标记的续段并回上一地址。
     */
    public static List<String> normalizeAffiliations(List<String> affiliations) {
        if (affiliations == null || affiliations.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String affiliation : affiliations) {
            if (affiliation == null || affiliation.isBlank()) {
                continue;
            }
            String value = affiliation.trim();
            String first = institutionName(value);
            boolean startsInstitution = INSTITUTION_MARKER.matcher(first).matches();
            if (!startsInstitution && !normalized.isEmpty()) {
                int last = normalized.size() - 1;
                normalized.set(last, normalized.get(last) + ", " + value);
            } else {
                normalized.add(value);
            }
        }
        return normalized;
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
            List<String> rawAffiliations = p.affiliations() == null ? List.of() : p.affiliations();
            // 参与国以原始地址逐段识别，避免机构地址续段合并后把第二个国家吞掉。
            for (String affiliation : rawAffiliations) {
                String country = parseCountry(affiliation);
                if (country != null) {
                    countries.add(country);
                }
            }
            List<String> affs = normalizeAffiliations(rawAffiliations);
            for (String aff : affs) {
                String country = parseCountry(aff);
                institutions.add(new InstitutionCountry(institutionName(aff), country));
            }
            if (countries.isEmpty() && p.firstAuthorCountry() != null) {
                countries.add(p.firstAuthorCountry()); // 地址无法解析时兜底
            }
            countriesByDoc.put(p.docId(), countries);
            institutionsByDoc.put(p.docId(), institutions);
        }

        // 高被引 h_i（按年份参照组，含并列分数分配）
        Map<String, Double> hByDoc = computeHighCitedWeights(scoped);

        // 分析主体固定为 CN/US；其他国家只用于国际合作及伙伴统计。
        Set<String> allCountries = new java.util.LinkedHashSet<>();
        for (String focusCountry : List.of("CN", "US")) {
            if (countriesByDoc.values().stream().anyMatch(countries -> countries.contains(focusCountry))) {
                allCountries.add(focusCountry);
            }
        }

        Map<String, BibliometricResult.CountryStats> byCountry = new LinkedHashMap<>();
        for (String country : allCountries) {
            byCountry.put(country, computeCountry(
                    country, scoped, countriesByDoc, institutionsByDoc, hByDoc, yearFrom, yearTo));
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
                                                                  Map<String, Double> hByDoc,
                                                                  int yearFrom,
                                                                  int yearTo) {
        long fullCount = 0;
        double fractionalCount = 0;
        long internationalCount = 0;
        long addressParsedPaperCount = 0;
        double highCited = 0;
        Map<Integer, Long> yearSeries = new LinkedHashMap<>();
        for (int year = yearFrom; year <= yearTo; year++) {
            yearSeries.put(year, 0L);
        }
        Map<String, Long> collaborationPartnerCounts = new LinkedHashMap<>();
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
            double countryWeight;
            if (totalInstitutions > 0 && countryInstitutions > 0) {
                countryWeight = (double) countryInstitutions / totalInstitutions;
            } else if (countries.size() == 1) {
                // 地址缺失或无法解析时，沿用经筛选确认的唯一国家归属；
                // 地址质量仍由 addressCoverageRatio 单独披露。
                countryWeight = 1.0;
            } else {
                countryWeight = 0.0;
            }
            fractionalCount += countryWeight;

            if (countries.size() >= 2) {
                internationalCount++;
                for (String partner : countries) {
                    if (!country.equals(partner)) {
                        collaborationPartnerCounts.merge(partner, 1L, Long::sum);
                    }
                }
            }

            Double h = hByDoc.get(p.docId());
            if (h != null) {
                highCited += countryWeight * h;
            }

            if (countryInstitutions > 0) {
                addressParsedPaperCount++;
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
                .collaborationPartnerCounts(collaborationPartnerCounts.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (left, right) -> left,
                                LinkedHashMap::new)))
                .highCitedCount(round(highCited))
                .highCitedRatio(fractionalCount > 0 ? round(highCited * 100.0 / fractionalCount) : 0)
                .yearSeries(yearSeries)
                .movingAverage3(movingAvg)
                .cagr(cagr == null ? null : round(cagr))
                .institutionShares(shares)
                .institutionHhi(round(hhi))
                .effectiveInstitutions(hhi > 0 ? round(1.0 / hhi) : 0)
                .addressParsedPaperCount(addressParsedPaperCount)
                .addressCoverageRatio(fullCount > 0 ? round(addressParsedPaperCount * 100.0 / fullCount) : 0)
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
