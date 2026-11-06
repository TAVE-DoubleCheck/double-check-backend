package kr.tave.minicore.ingest;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 전처리 규칙 (기능명세서 2.3). 예상과 다른 형식은 추측해서 변환하지 않고 예외로 멈춘다.
 */
public final class Normalizer {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern WON = Pattern.compile("^([0-9,]+)원$");
    private static final Pattern MAN_WON = Pattern.compile("^([0-9,]+)만원$");
    private static final Pattern YEARS = Pattern.compile("^([0-9]+)년$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
    private static final Pattern TERMS_CODE_IN_URL = Pattern.compile("[?&]p=([A-Za-z0-9]+)");
    public static final String FULL_TERM_PAY = "전기납";

    private Normalizer() {
    }

    /** "33070원" → 33070 */
    public static long parseWon(String raw) {
        Matcher m = WON.matcher(strip(raw));
        if (!m.matches()) {
            throw new IllegalArgumentException("보험료 형식이 '숫자원'이 아님: '" + raw + "'");
        }
        return positive(Long.parseLong(m.group(1).replace(",", "")), raw);
    }

    /** "1000만원" → 10,000,000. "N원"도 허용. 그 외(억 등)는 관찰되지 않았으므로 예외. */
    public static long parseUnitAmount(String raw) {
        String s = strip(raw);
        Matcher man = MAN_WON.matcher(s);
        if (man.matches()) {
            return positive(Math.multiplyExact(Long.parseLong(man.group(1).replace(",", "")), 10_000L), raw);
        }
        Matcher won = WON.matcher(s);
        if (won.matches()) {
            return positive(Long.parseLong(won.group(1).replace(",", "")), raw);
        }
        throw new IllegalArgumentException("기준단위금액 형식을 알 수 없음: '" + raw + "'");
    }

    /** 보험기간 원본 코드. 숫자만 허용하고 의미(세 만기/년 만기)는 해석하지 않는다. */
    public static String insureTermCode(String raw) {
        String s = strip(raw);
        if (!DIGITS.matcher(s).matches()) {
            throw new IllegalArgumentException("보험기간이 숫자가 아님: '" + raw + "'");
        }
        return s;
    }

    /** "10년" → 10, "전기납" → 보험기간 값으로 치환 (명세서 규칙) */
    public static int payTermYears(String rawPayTerm, String insureTermCode) {
        String s = strip(rawPayTerm);
        if (FULL_TERM_PAY.equals(s)) {
            return Integer.parseInt(insureTermCode(insureTermCode));
        }
        Matcher m = YEARS.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("납입기간 형식을 알 수 없음: '" + rawPayTerm + "'");
        }
        return Integer.parseInt(m.group(1));
    }

    public static boolean isFullTermPay(String rawPayTerm) {
        return FULL_TERM_PAY.equals(strip(rawPayTerm));
    }

    /** "20260916" → 2026-09-16 */
    public static LocalDate parseYmd(String raw) {
        try {
            return LocalDate.parse(strip(raw), YMD);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("날짜 형식(yyyyMMdd)이 아님: '" + raw + "'", e);
        }
    }

    /** 적용종료일. "99991231"은 판매 중 → null */
    public static LocalDate parseEndYmd(String raw) {
        String s = strip(raw);
        if (s.isEmpty() || "99991231".equals(s)) {
            return null;
        }
        return parseYmd(s);
    }

    /** STPL_URL_ADDR의 p 파라미터 → 약관코드 (예: ...terms.do?p=P400084&d=20261027 → P400084) */
    public static String termsCodeFromUrl(String url) {
        Matcher m = TERMS_CODE_IN_URL.matcher(strip(url));
        if (!m.find()) {
            throw new IllegalArgumentException("약관 URL에 p 파라미터가 없음: '" + url + "'");
        }
        return m.group(1);
    }

    /** 성별 M/W만 허용 */
    public static String sex(String raw) {
        String s = strip(raw);
        if (!s.equals("M") && !s.equals("W")) {
            throw new IllegalArgumentException("성별이 M/W가 아님: '" + raw + "'");
        }
        return s;
    }

    public static int age(String raw) {
        String s = strip(raw);
        if (!DIGITS.matcher(s).matches()) {
            throw new IllegalArgumentException("가입나이가 숫자가 아님: '" + raw + "'");
        }
        return Integer.parseInt(s);
    }

    /** 보장 API 급부명: "■ 사망보험금" → "사망보험금" */
    public static String benefitName(String raw) {
        return strip(raw).replaceFirst("^[■□●○◆◇▶\\s]+", "").trim();
    }

    private static long positive(long v, String raw) {
        if (v <= 0) {
            throw new IllegalArgumentException("0 이하 금액: '" + raw + "'");
        }
        return v;
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }
}
