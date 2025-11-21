package com.skax.aiplatform.common.util;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.springframework.web.multipart.MultipartFile;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

/**
 * 업로드된 파일의 헤더를 읽어 MIME 타입과 인코딩을 검증하는 유틸리티.
 */
public final class MultipartFileHeaderChecker {

    // 헤더 읽기 크기 (파일 헤더만 읽기 위해 충분한 크기)
    private static final int HEADER_READ_BYTES = 8192;
    private static final int CSV_SNIFF_BYTES = 4096;
    private static final int CHARSET_SAMPLE_BYTES = 8192; // 헤더만 읽도록 변경
    private static final int COPY_BUFFER_SIZE = 8192;

    // 허용 파일 확장자 목록
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".ppt", ".pptx", ".pdf", ".doc", ".docx",
            ".xls", ".xlsx", ".png", ".jpg", ".jpeg",
            ".txt", ".zip", ".csv", ".xml");

    // 파일 확장자
    private static final String EXT_XLSX = ".xlsx";
    private static final String EXT_XLS = ".xls";
    private static final String EXT_CSV = ".csv";

    // MIME 타입
    private static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String MIME_TIKA_OOXML = "application/x-tika-ooxml";
    private static final String MIME_WORD = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String MIME_PRESENTATION = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    // 인코딩
    private static final String ENC_UTF8 = "UTF-8";
    private static final String ENC_UTF8_SIG = "UTF-8-SIG";

    // 성공 메시지
    private static final String MSG_SUCCESS = "검증 성공";
    private static final String MSG_SUCCESS_CSV = "검증 성공(CSV, UTF-8)";
    private static final String MSG_SUCCESS_EXCEL = "검증 성공(Excel)";

    // 에러 메시지
    private static final String ERR_EMPTY_FILE = "빈 파일입니다.";
    private static final String ERR_INVALID_EXTENSION = "허용되지 않은 파일 확장자입니다. 허용 확장자: ";
    private static final String ERR_UNSUPPORTED_TYPE = "지원하지 않는 파일 타입입니다. (감지된 MIME: ";
    private static final String ERR_FILE_PROCESS = "파일 처리 오류: ";
    private static final String ERR_CSV_ENCODING_UNKNOWN = "CSV 인코딩을 판별할 수 없습니다.";
    private static final String ERR_CSV_ENCODING_INVALID = "CSV는 UTF-8 이어야 합니다. (감지: ";
    private static final String ERR_CSV_EMPTY = "CSV 내용이 비어 있습니다.";
    private static final String ERR_CSV_FORMAT_INVALID = "파일 확장자가 .csv이지만 실제 파일 내용이 CSV 형식이 아닙니다.";
    private static final String ERR_EXTENSION_MIME_MISMATCH = "파일 확장자와 실제 파일 형식(MIME 타입)이 일치하지 않습니다. 확장자: ";
    private static final String ERR_XLSX_INVALID = "XLSX 포맷 오류: ";
    private static final String ERR_XLS_INVALID = "XLS 포맷 오류: ";
    private static final String ERR_EXCEL_INVALID = "Excel 포맷 오류: ";
    private static final String ERR_DETAIL_NO_FILENAME = "파일명이 없습니다.";
    private static final String ERR_DETAIL_FILE_TOO_SMALL = "파일이 너무 작습니다.";
    private static final String ERR_DETAIL_INVALID_XLSX = "올바른 XLSX 파일이 아닙니다.";
    private static final String ERR_DETAIL_INVALID_XLS = "올바른 XLS 파일이 아닙니다.";
    private static final String ERR_DETAIL_INVALID_EXCEL = "올바른 Excel 파일이 아닙니다.";
    private static final String ERR_DETAIL_FILE_READ = "파일을 읽을 수 없습니다.";
    private static final String ERR_SUFFIX_CLOSE_PAREN = ")";
    private static final String ERR_DETAIL_PATH_DELETE = "파일 삭제에 실패했습니다: ";
    private static final String ERR_DETAIL_POST_VALIDATION_DELETE = "검증 후 파일 삭제에 실패했습니다: ";
    private static final String ERR_DETAIL_OOXML_REFINE = "OOXML MIME 정제에 실패했습니다.";
    private static final String ERR_DETAIL_HEADER_READ = "파일 헤더를 읽는 중 오류가 발생했습니다: ";

    // OOXML 관련
    private static final String OOXML_CONTENT_TYPES_XML = "[Content_Types].xml";
    private static final String OOXML_SPREADSHEET_MAIN = "spreadsheetml.sheet.main+xml";
    private static final String OOXML_WORD_MAIN = "wordprocessingml.document.main+xml";
    private static final String OOXML_PRESENTATION_MAIN = "presentationml.presentation.main+xml";

    private MultipartFileHeaderChecker() {
    }

    /**
     * 지원 파일 포맷 유형.
     */
    public enum FileType {
        CSV,
        EXCEL,
        DOCUMENT,
        PRESENTATION,
        PDF,
        IMAGE,
        TEXT,
        ARCHIVE,
        XML,
        OTHER
    }

    /**
     * 검증 결과.
     */
    public record FileCheckResult(boolean ok, String message, String mimeType, FileType fileType, String encoding) {
    }

    /**
     * 업로드된 멀티파트 파일의 헤더를 검증한다.
     *
     * @param file 검증할 파일
     * @return 검증 결과
     */
    public static FileCheckResult validate(MultipartFile file) {
        return validate(file, false);
    }

    /**
     * 업로드된 멀티파트 파일의 헤더를 검증한다.
     * 대용량 파일 지원: 헤더만 읽어서 검증하므로 메모리 효율적입니다.
     *
     * @param file              검증할 파일
     * @param checkUTF8Encoding CSV/XLS/XLSX 파일의 UTF-8 인코딩 검증 여부
     * @return 검증 결과
     */
    public static FileCheckResult validate(MultipartFile file, boolean checkUTF8Encoding) {
        if (file == null || file.isEmpty()) {
            return fail(ERR_EMPTY_FILE);
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null) {
            return fail(ERR_FILE_PROCESS + ERR_DETAIL_NO_FILENAME);
        }

        // 파일 확장자 유효성 체크
        String extension = extractExtension(originalName);
        if (!isAllowedExtension(extension)) {
            return fail(ERR_INVALID_EXTENSION + String.join(", ", ALLOWED_EXTENSIONS));
        }

        // 대용량 파일 지원: InputStream에서 헤더만 읽어서 검증
        // 임시 파일을 만들지 않아 디스크 공간을 절약합니다
        try (InputStream inputStream = file.getInputStream()) {
            return validateFromInputStream(inputStream, originalName, checkUTF8Encoding);
        } catch (IOException e) {
            return fail(ERR_FILE_PROCESS + e.getMessage());
        }
    }

    /**
     * InputStream에서 헤더만 읽어서 파일을 검증합니다 (대용량 파일 지원).
     *
     * @param inputStream       파일 InputStream
     * @param originalName      원본 파일명
     * @param checkUTF8Encoding UTF-8 인코딩 검증 여부
     * @return 검증 결과
     */
    private static FileCheckResult validateFromInputStream(InputStream inputStream, String originalName,
                                                           boolean checkUTF8Encoding) throws IOException {
        // InputStream을 mark/reset 지원하는 BufferedInputStream으로 래핑
        if (!inputStream.markSupported()) {
            inputStream = new java.io.BufferedInputStream(inputStream, HEADER_READ_BYTES * 2);
        }

        // 헤더만 읽기 (최대 HEADER_READ_BYTES 바이트)
        inputStream.mark(HEADER_READ_BYTES * 2);
        byte[] header = inputStream.readNBytes(HEADER_READ_BYTES);
        inputStream.reset(); // 이후 사용을 위해 reset

        if (header.length == 0) {
            return fail(ERR_DETAIL_FILE_READ);
        }

        // MIME 타입 감지
        String mime = detectMimeFromHeaderBytes(header, originalName);

        // 확장자와 MIME 타입 일치 검증
        String extension = extractExtension(originalName);
        FileCheckResult extensionMimeCheck = validateExtensionMimeMatchFromBytes(extension, mime, header, inputStream,
                originalName);
        if (!extensionMimeCheck.ok()) {
            return extensionMimeCheck;
        }

        // 파일 타입 결정 (path가 null이므로 확장자와 MIME 타입만으로 판단)
        FileType fileType = determineFileTypeFromExtensionAndMime(mime, extension);
        if (fileType == null) {
            return fail(ERR_UNSUPPORTED_TYPE + mime + ERR_SUFFIX_CLOSE_PAREN);
        }

        // CSV/XLS/XLSX 파일이고 인코딩 체크가 필요한 경우
        if (checkUTF8Encoding) {
            if (EXT_CSV.equalsIgnoreCase(extension)) {
                // CSV 형식 확인을 위해 더 읽기
                inputStream.mark(CSV_SNIFF_BYTES);
                byte[] csvHeader = inputStream.readNBytes(CSV_SNIFF_BYTES);
                inputStream.reset();

                if (!looksLikeCsvFromBytes(csvHeader)) {
                    return fail(ERR_CSV_FORMAT_INVALID);
                }

                // 인코딩 검증
                String detected = detectCharsetFromBytes(csvHeader);
                String normalized = normalizeUtf8FromBytes(detected, csvHeader);
                if (normalized == null) {
                    return fail(ERR_CSV_ENCODING_UNKNOWN);
                }
                if (!normalized.equalsIgnoreCase(ENC_UTF8) && !normalized.equalsIgnoreCase(ENC_UTF8_SIG)) {
                    return fail(ERR_CSV_ENCODING_INVALID + detected + ERR_SUFFIX_CLOSE_PAREN);
                }

                // 빈 파일 체크를 위해 헤더 바이트에서 첫 줄 확인
                // BufferedReader를 사용하면 내부 InputStream이 닫히므로,
                // 바이트 배열을 직접 UTF-8로 디코딩하여 확인
                inputStream.mark(CHARSET_SAMPLE_BYTES);
                byte[] firstLineBytes = inputStream.readNBytes(CHARSET_SAMPLE_BYTES);
                inputStream.reset();

                // BOM 제거 (UTF-8-SIG인 경우)
                if (firstLineBytes.length >= 3 &&
                        firstLineBytes[0] == (byte) 0xEF &&
                        firstLineBytes[1] == (byte) 0xBB &&
                        firstLineBytes[2] == (byte) 0xBF) {
                    firstLineBytes = Arrays.copyOfRange(firstLineBytes, 3, firstLineBytes.length);
                }

                // UTF-8로 디코딩하여 첫 줄 확인
                String firstLine = new String(firstLineBytes, StandardCharsets.UTF_8);
                // 줄바꿈 문자로 첫 줄 추출
                int newlineIndex = firstLine.indexOf('\n');
                if (newlineIndex >= 0) {
                    firstLine = firstLine.substring(0, newlineIndex);
                }
                // 캐리지 리턴 제거
                firstLine = firstLine.replace("\r", "").trim();

                if (firstLine.isEmpty()) {
                    return fail(ERR_CSV_EMPTY);
                }

                return ok(MSG_SUCCESS_CSV, mime, FileType.CSV, normalized);
            } else if (EXT_XLS.equalsIgnoreCase(extension) || EXT_XLSX.equalsIgnoreCase(extension)) {
                // Excel 파일은 헤더 시그니처만 확인
                if (EXT_XLSX.equalsIgnoreCase(extension)) {
                    if (header.length < 4 || header[0] != 0x50 || header[1] != 0x4B ||
                            header[2] != 0x03 || header[3] != 0x04) {
                        return fail(ERR_XLSX_INVALID + ERR_DETAIL_INVALID_XLSX);
                    }
                } else if (EXT_XLS.equalsIgnoreCase(extension)) {
                    if (header.length < 8 || header[0] != (byte) 0xD0 || header[1] != (byte) 0xCF ||
                            header[2] != 0x11 || header[3] != (byte) 0xE0) {
                        return fail(ERR_XLS_INVALID + ERR_DETAIL_INVALID_XLS);
                    }
                }
                return ok(MSG_SUCCESS_EXCEL, mime, FileType.EXCEL, null);
            }
        } else {
            // 인코딩 체크가 필요 없더라도 확장자가 .csv인 경우 CSV 형식인지 확인
            if (EXT_CSV.equalsIgnoreCase(extension)) {
                inputStream.mark(CSV_SNIFF_BYTES);
                byte[] csvHeader = inputStream.readNBytes(CSV_SNIFF_BYTES);
                inputStream.reset();

                if (!looksLikeCsvFromBytes(csvHeader)) {
                    return fail(ERR_CSV_FORMAT_INVALID);
                }
            }
        }

        // 일반적인 검증 성공
        return ok(MSG_SUCCESS, mime, fileType, null);
    }

    /**
     * 바이트 배열에서 MIME 타입을 감지합니다.
     */
    private static String detectMimeFromHeaderBytes(byte[] header, String originalName) throws IOException {
        DefaultDetector detector = new DefaultDetector();
        Metadata metadata = new Metadata();
        if (originalName != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalName);
        }

        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(header);
             TikaInputStream stream = TikaInputStream.get(byteStream)) {
            MediaType mediaType = detector.detect(stream, metadata);
            String mime = mediaType.toString();
            if (MIME_TIKA_OOXML.equalsIgnoreCase(mime)) {
                return refineOoxmlMime(header, mime);
            }
            return mime;
        }
    }

    /**
     * 바이트 배열에서 확장자와 MIME 타입 일치 여부를 검증합니다.
     */
    private static FileCheckResult validateExtensionMimeMatchFromBytes(String extension, String mime,
                                                                       byte[] header, InputStream inputStream, String originalName) throws IOException {
        if (extension == null || extension.isEmpty() || mime == null) {
            return ok(MSG_SUCCESS, mime, null, null);
        }

        String extLower = extension.toLowerCase();
        String mimeLower = mime.toLowerCase();

        // 확장자별 예상 MIME 타입과 실제 감지된 MIME 타입 비교
        // 파일 시그니처는 이미 header에 있으므로 추가 읽기 불필요
        switch (extLower) {
            case ".xlsx":
                if (!mimeLower.contains("spreadsheet") && !mimeLower.contains("zip") &&
                        !mimeLower.contains("ooxml") && !mimeLower.contains("ms-excel")) {
                    if (header.length >= 4 && header[0] == 0x50 && header[1] == 0x4B) {
                        // ZIP 시그니처 확인
                        if (header.length >= 1024) {
                            String headerStr = new String(header, 0, Math.min(1024, header.length),
                                    StandardCharsets.ISO_8859_1);
                            if (headerStr.contains(OOXML_SPREADSHEET_MAIN)) {
                                return ok(MSG_SUCCESS, mime, null, null);
                            }
                        }
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".xls":
                if (!mimeLower.contains("ms-excel") && !mimeLower.contains("spreadsheet")) {
                    if (header.length >= 8 && header[0] == (byte) 0xD0 && header[1] == (byte) 0xCF &&
                            header[2] == 0x11 && header[3] == (byte) 0xE0) {
                        return ok(MSG_SUCCESS, mime, null, null);
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".csv":
                // MIME 타입이 CSV 관련이 아닌 경우, 실제 파일 내용 확인
                if (!mimeLower.contains("csv") && !mimeLower.contains("text/plain") &&
                        !mimeLower.contains("text/comma-separated-values")) {
                    if (looksLikeCsvFromBytes(header)) {
                        return ok(MSG_SUCCESS, mime, null, null);
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                // MIME 타입이 CSV 관련이더라도 실제 파일 내용이 CSV 형식인지 확인
                // (확장자와 MIME이 일치해도 실제 내용이 다를 수 있음)
                if (!looksLikeCsvFromBytes(header)) {
                    return fail(ERR_CSV_FORMAT_INVALID);
                }
                break;

            case ".pdf":
                if (!mimeLower.contains("pdf")) {
                    if (header.length >= 4 && header[0] == 0x25 && header[1] == 0x50 &&
                            header[2] == 0x44 && header[3] == 0x46) {
                        return ok(MSG_SUCCESS, mime, null, null);
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".png":
                if (!mimeLower.contains("image/png")) {
                    if (header.length >= 4 && header[0] == (byte) 0x89 && header[1] == 0x50 &&
                            header[2] == 0x4E && header[3] == 0x47) {
                        return ok(MSG_SUCCESS, mime, null, null);
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".jpg":
            case ".jpeg":
                if (!mimeLower.contains("image/jpeg")) {
                    if (header.length >= 3 && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 &&
                            header[2] == (byte) 0xFF) {
                        return ok(MSG_SUCCESS, mime, null, null);
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".zip":
                if (!mimeLower.contains("zip")) {
                    if (header.length >= 4 && header[0] == 0x50 && header[1] == 0x4B &&
                            header[2] == 0x03 && header[3] == 0x04) {
                        return ok(MSG_SUCCESS, mime, null, null);
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".doc":
                // DOC는 msword MIME 타입이어야 함
                if (!mimeLower.contains("msword") && !mimeLower.contains("application/msword")) {
                    // 헤더만 읽어서 실제 DOC 파일인지 확인 (OLE2 포맷)
                    if (header.length >= 8 && header[0] == (byte) 0xD0 && header[1] == (byte) 0xCF &&
                            header[2] == 0x11 && header[3] == (byte) 0xE0) {
                        return ok(MSG_SUCCESS, mime, null, null);
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".docx":
                // DOCX는 wordprocessingml MIME 타입이어야 함
                if (!mimeLower.contains("wordprocessingml") && !mimeLower.contains("msword") &&
                        !mimeLower.contains("ooxml")) {
                    // 헤더만 읽어서 실제 DOCX 파일인지 확인
                    if (header.length >= 4 && header[0] == 0x50 && header[1] == 0x4B) {
                        // ZIP 시그니처 확인
                        if (header.length >= 1024) {
                            String headerStr = new String(header, 0, Math.min(1024, header.length),
                                    StandardCharsets.ISO_8859_1);
                            if (headerStr.contains(OOXML_WORD_MAIN)) {
                                return ok(MSG_SUCCESS, mime, null, null);
                            }
                        }
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".ppt":
                // PPT는 ms-powerpoint MIME 타입이어야 함
                if (!mimeLower.contains("ms-powerpoint") && !mimeLower.contains("application/vnd.ms-powerpoint")) {
                    // 헤더만 읽어서 실제 PPT 파일인지 확인 (OLE2 포맷)
                    if (header.length >= 8 && header[0] == (byte) 0xD0 && header[1] == (byte) 0xCF &&
                            header[2] == 0x11 && header[3] == (byte) 0xE0) {
                        return ok(MSG_SUCCESS, mime, null, null);
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".pptx":
                // PPTX는 presentationml MIME 타입이어야 함
                if (!mimeLower.contains("presentationml") && !mimeLower.contains("ms-powerpoint") &&
                        !mimeLower.contains("ooxml")) {
                    // 헤더만 읽어서 실제 PPTX 파일인지 확인
                    if (header.length >= 4 && header[0] == 0x50 && header[1] == 0x4B) {
                        // ZIP 시그니처 확인
                        if (header.length >= 1024) {
                            String headerStr = new String(header, 0, Math.min(1024, header.length),
                                    StandardCharsets.ISO_8859_1);
                            if (headerStr.contains(OOXML_PRESENTATION_MAIN)) {
                                return ok(MSG_SUCCESS, mime, null, null);
                            }
                        }
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".txt":
                // TXT는 text/plain MIME 타입이어야 함
                if (!mimeLower.contains("text/plain") && !mimeLower.contains("text")) {
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".xml":
                // XML은 application/xml 또는 text/xml MIME 타입이어야 함
                if (!mimeLower.contains("xml")) {
                    // 헤더만 읽어서 실제 XML 파일인지 확인 (XML 선언: <?xml)
                    if (header.length >= 5) {
                        String headerStr = new String(header, 0, Math.min(5, header.length), StandardCharsets.UTF_8)
                                .trim();
                        if (headerStr.startsWith("<?xml") || headerStr.startsWith("<")) {
                            return ok(MSG_SUCCESS, mime, null, null);
                        }
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            default:
                // 허용된 확장자인데 switch에 없는 경우는 개발 오류이지만, 안전을 위해 MIME 타입 불일치로 처리
                // 허용되지 않은 확장자는 이미 앞에서 걸러졌으므로 여기 도달하면 안 됨
                if (ALLOWED_EXTENSIONS.contains(extLower)) {
                    // 허용된 확장자인데 검증 로직이 없는 경우 - 개발 오류
                    // 안전을 위해 MIME 타입 불일치로 처리
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime + " (검증 로직 누락)");
                }
                // 허용되지 않은 확장자는 이미 앞에서 걸러졌으므로 여기 도달하면 안 됨
                break;
        }

        return ok(MSG_SUCCESS, mime, null, null);
    }

    /**
     * 바이트 배열에서 CSV 형식인지 확인합니다.
     */
    private static boolean looksLikeCsvFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        byte[] stripped = stripBomIfNecessary(bytes);
        String sample = new String(stripped, 0, Math.min(stripped.length, CSV_SNIFF_BYTES),
                StandardCharsets.ISO_8859_1);
        boolean hasDelimiter = sample.contains(",") || sample.contains(";") || sample.contains("\t");
        boolean hasNewLine = sample.contains("\n") || sample.contains("\r");
        return hasDelimiter && hasNewLine;
    }

    /**
     * 바이트 배열에서 인코딩을 감지합니다.
     */
    private static String detectCharsetFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            CharsetDetector detector = new CharsetDetector();
            detector.setText(bytes);
            CharsetMatch match = detector.detect();
            if (match != null && match.getConfidence() > 0) {
                return match.getName();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 바이트 배열에서 UTF-8 인코딩을 정규화합니다.
     */
    private static String normalizeUtf8FromBytes(String detected, byte[] bytes) {
        try {
            if (hasUtf8Bom(bytes)) {
                return ENC_UTF8_SIG;
            }
            if (detected == null) {
                return null;
            }
            // 감지된 인코딩이 UTF-8 계열이 아니면 그대로 반환 (검증 실패용)
            if (!detected.equalsIgnoreCase(ENC_UTF8) && !detected.equalsIgnoreCase(ENC_UTF8_SIG)) {
                return detected; // UTF-8이 아니므로 원래 인코딩 반환
            }
            // UTF-8로 디코딩 가능한지 엄격하게 확인
            // MalformedInputException을 발생시키기 위해 Decoder를 사용
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            decoder.decode(java.nio.ByteBuffer.wrap(bytes, 0, Math.min(bytes.length, COPY_BUFFER_SIZE)));
            // 디코딩 성공 시 UTF-8로 간주
            return ENC_UTF8;
        } catch (java.nio.charset.CharacterCodingException e) {
            // UTF-8로 디코딩 실패 - 원래 감지된 인코딩 반환
            return detected;
        } catch (Exception e) {
            return detected;
        }
    }

    /**
     * 파일 경로로 직접 파일 헤더를 검증한다.
     * 큰 파일을 처리할 때 메모리 효율적이다.
     *
     * @param filePath              검증할 파일의 경로
     * @param originalName          원본 파일명 (확장자 판별을 위해 사용)
     * @param deleteAfterValidation 검증 후 파일을 삭제할지 여부
     * @return 검증 결과
     */
    public static FileCheckResult validate(Path filePath, String originalName, boolean deleteAfterValidation) {
        return validate(filePath, originalName, deleteAfterValidation, false);
    }

    /**
     * 파일 경로로 직접 파일 헤더를 검증한다.
     *
     * @param filePath              검증할 파일의 경로
     * @param originalName          원본 파일명 (확장자 판별을 위해 사용)
     * @param deleteAfterValidation 검증 후 파일을 삭제할지 여부
     * @param checkUTF8Encoding     CSV/XLS/XLSX 파일의 UTF-8 인코딩 검증 여부
     * @return 검증 결과
     */
    public static FileCheckResult validate(Path filePath, String originalName, boolean deleteAfterValidation,
                                           boolean checkUTF8Encoding) {
        if (filePath == null || !Files.exists(filePath)) {
            return fail(ERR_EMPTY_FILE);
        }

        if (originalName == null) {
            return fail(ERR_FILE_PROCESS + ERR_DETAIL_NO_FILENAME);
        }

        // 파일 확장자 유효성 체크
        String extension = extractExtension(originalName);
        if (!isAllowedExtension(extension)) {
            return fail(ERR_INVALID_EXTENSION + String.join(", ", ALLOWED_EXTENSIONS));
        }

        try {
            return validatePath(filePath, originalName, deleteAfterValidation, checkUTF8Encoding);
        } catch (IOException e) {
            return fail(ERR_FILE_PROCESS + e.getMessage());
        } finally {
            if (deleteAfterValidation) {
                try {
                    Files.deleteIfExists(filePath);
                } catch (IOException cleanupError) {
                    throw new IllegalStateException(ERR_DETAIL_PATH_DELETE + filePath, cleanupError);
                }
            }
        }
    }

    /**
     * 파일 경로로 직접 파일 헤더를 검증한다 (파일 삭제 안 함).
     *
     * @param filePath     검증할 파일의 경로
     * @param originalName 원본 파일명 (확장자 판별을 위해 사용)
     * @return 검증 결과
     */
    public static FileCheckResult validate(Path filePath, String originalName) {
        return validate(filePath, originalName, false, false);
    }

    /**
     * 내부 검증 로직 (공통).
     */
    private static FileCheckResult validatePath(Path path, String originalName, boolean deleteAfterValidation,
                                                boolean checkUTF8Encoding) throws IOException {
        try {
            // 파일 헤더만 읽어서 MIME 타입 감지
            String mime = detectMimeFromHeader(path, originalName);
            String extension = extractExtension(originalName);

            // 확장자와 MIME 타입이 일치하는지 확인
            FileCheckResult extensionMimeCheck = validateExtensionMimeMatch(extension, mime, path, originalName);
            if (!extensionMimeCheck.ok()) {
                return extensionMimeCheck;
            }

            FileType fileType = determineFileType(mime, path, originalName);
            if (fileType == null) {
                return fail(ERR_UNSUPPORTED_TYPE + mime + ERR_SUFFIX_CLOSE_PAREN);
            }

            // CSV/XLS/XLSX 파일이고 인코딩 체크가 필요한 경우
            if (checkUTF8Encoding) {
                if (EXT_CSV.equalsIgnoreCase(extension)) {
                    // 확장자가 .csv인 경우, 실제 파일 내용이 CSV 형식인지 확인
                    if (!looksLikeCsv(path)) {
                        return fail(ERR_CSV_FORMAT_INVALID);
                    }
                    return validateCsv(path, mime);
                } else if (EXT_XLS.equalsIgnoreCase(extension) || EXT_XLSX.equalsIgnoreCase(extension)) {
                    return validateExcel(path, mime, originalName);
                }
            } else {
                // 인코딩 체크가 필요 없더라도 확장자가 .csv인 경우 CSV 형식인지 확인
                if (EXT_CSV.equalsIgnoreCase(extension)) {
                    if (!looksLikeCsv(path)) {
                        return fail(ERR_CSV_FORMAT_INVALID);
                    }
                }
            }

            // 일반적인 검증 성공
            return ok(MSG_SUCCESS, mime, fileType, null);
        } finally {
            if (deleteAfterValidation) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanupError) {
                    throw new IllegalStateException(ERR_DETAIL_POST_VALIDATION_DELETE + path, cleanupError);
                }
            }
        }
    }

    /**
     * 허용된 파일 확장자인지 확인.
     */
    private static boolean isAllowedExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return false;
        }
        return ALLOWED_EXTENSIONS.contains(extension.toLowerCase());
    }

    /**
     * 확장자와 MIME 타입이 일치하는지 검증 (헤더만 읽어서 검증).
     */
    private static FileCheckResult validateExtensionMimeMatch(String extension, String mime, Path path,
                                                              String originalName) throws IOException {
        if (extension == null || extension.isEmpty() || mime == null) {
            return ok(MSG_SUCCESS, mime, null, null);
        }

        String extLower = extension.toLowerCase();
        String mimeLower = mime.toLowerCase();

        // 확장자별 예상 MIME 타입과 실제 감지된 MIME 타입 비교
        switch (extLower) {
            case ".xlsx":
                // XLSX는 ZIP 기반이므로 application/zip 또는 spreadsheet MIME 타입이어야 함
                if (!mimeLower.contains("spreadsheet") && !mimeLower.contains("zip") &&
                        !mimeLower.contains("ooxml") && !mimeLower.contains("ms-excel")) {
                    // 헤더만 읽어서 실제 XLSX 파일인지 확인
                    try {
                        byte[] header = readHead(path, 4);
                        if (header.length >= 4 && header[0] == 0x50 && header[1] == 0x4B) {
                            // ZIP 시그니처가 있으므로 XLSX일 가능성이 높음
                            // 더 정확한 검증을 위해 헤더에서 spreadsheet 확인
                            byte[] moreHeader = readHead(path, 1024);
                            String headerStr = new String(moreHeader, StandardCharsets.ISO_8859_1);
                            if (headerStr.contains(OOXML_SPREADSHEET_MAIN)) {
                                // 실제로 XLSX 파일이므로 통과
                                return ok(MSG_SUCCESS, mime, null, null);
                            }
                        }
                    } catch (Exception e) {
                        // 헤더 읽기 실패 시 MIME 타입 불일치로 간주
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".xls":
                // XLS는 OLE2 포맷이므로 ms-excel MIME 타입이어야 함
                if (!mimeLower.contains("ms-excel") && !mimeLower.contains("spreadsheet")) {
                    // 헤더만 읽어서 실제 XLS 파일인지 확인
                    try {
                        byte[] header = readHead(path, 8);
                        if (header.length >= 8 && header[0] == (byte) 0xD0 && header[1] == (byte) 0xCF &&
                                header[2] == 0x11 && header[3] == (byte) 0xE0) {
                            // OLE2 시그니처가 있으므로 XLS 파일임
                            return ok(MSG_SUCCESS, mime, null, null);
                        }
                    } catch (Exception e) {
                        // 헤더 읽기 실패 시 MIME 타입 불일치로 간주
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".csv":
                // CSV는 text/csv 또는 text/plain MIME 타입이어야 함
                if (!mimeLower.contains("csv") && !mimeLower.contains("text/plain") &&
                        !mimeLower.contains("text/comma-separated-values")) {
                    // 헤더만 읽어서 실제 CSV 파일인지 확인
                    if (!looksLikeCsv(path)) {
                        return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                    }
                }
                break;

            case ".docx":
                // DOCX는 wordprocessingml MIME 타입이어야 함
                if (!mimeLower.contains("wordprocessingml") && !mimeLower.contains("msword") &&
                        !mimeLower.contains("ooxml")) {
                    // 헤더만 읽어서 실제 DOCX 파일인지 확인
                    try {
                        byte[] header = readHead(path, 4);
                        if (header.length >= 4 && header[0] == 0x50 && header[1] == 0x4B) {
                            byte[] moreHeader = readHead(path, 1024);
                            String headerStr = new String(moreHeader, StandardCharsets.ISO_8859_1);
                            if (headerStr.contains(OOXML_WORD_MAIN)) {
                                return ok(MSG_SUCCESS, mime, null, null);
                            }
                        }
                    } catch (Exception e) {
                        // 헤더 읽기 실패 시 MIME 타입 불일치로 간주
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".doc":
                // DOC는 msword MIME 타입이어야 함
                if (!mimeLower.contains("msword") && !mimeLower.contains("application/msword")) {
                    // 헤더만 읽어서 실제 DOC 파일인지 확인 (OLE2 포맷)
                    try {
                        byte[] header = readHead(path, 8);
                        if (header.length >= 8 && header[0] == (byte) 0xD0 && header[1] == (byte) 0xCF &&
                                header[2] == 0x11 && header[3] == (byte) 0xE0) {
                            return ok(MSG_SUCCESS, mime, null, null);
                        }
                    } catch (Exception e) {
                        // 헤더 읽기 실패 시 MIME 타입 불일치로 간주
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".pptx":
                // PPTX는 presentationml MIME 타입이어야 함
                if (!mimeLower.contains("presentationml") && !mimeLower.contains("ms-powerpoint") &&
                        !mimeLower.contains("ooxml")) {
                    // 헤더만 읽어서 실제 PPTX 파일인지 확인
                    try {
                        byte[] header = readHead(path, 4);
                        if (header.length >= 4 && header[0] == 0x50 && header[1] == 0x4B) {
                            byte[] moreHeader = readHead(path, 1024);
                            String headerStr = new String(moreHeader, StandardCharsets.ISO_8859_1);
                            if (headerStr.contains(OOXML_PRESENTATION_MAIN)) {
                                return ok(MSG_SUCCESS, mime, null, null);
                            }
                        }
                    } catch (Exception e) {
                        // 헤더 읽기 실패 시 MIME 타입 불일치로 간주
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".ppt":
                // PPT는 ms-powerpoint MIME 타입이어야 함
                if (!mimeLower.contains("ms-powerpoint") && !mimeLower.contains("application/vnd.ms-powerpoint")) {
                    // 헤더만 읽어서 실제 PPT 파일인지 확인 (OLE2 포맷)
                    try {
                        byte[] header = readHead(path, 8);
                        if (header.length >= 8 && header[0] == (byte) 0xD0 && header[1] == (byte) 0xCF &&
                                header[2] == 0x11 && header[3] == (byte) 0xE0) {
                            return ok(MSG_SUCCESS, mime, null, null);
                        }
                    } catch (Exception e) {
                        // 헤더 읽기 실패 시 MIME 타입 불일치로 간주
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".pdf":
                // PDF는 application/pdf MIME 타입이어야 함
                if (!mimeLower.contains("pdf")) {
                    // 헤더만 읽어서 실제 PDF 파일인지 확인 (PDF 시그니처: %PDF)
                    try {
                        byte[] header = readHead(path, 4);
                        if (header.length >= 4 && header[0] == 0x25 && header[1] == 0x50 &&
                                header[2] == 0x44 && header[3] == 0x46) {
                            return ok(MSG_SUCCESS, mime, null, null);
                        }
                    } catch (Exception e) {
                        // 헤더 읽기 실패 시 MIME 타입 불일치로 간주
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".png":
                // PNG는 image/png MIME 타입이어야 함
                if (!mimeLower.contains("image/png")) {
                    // 헤더만 읽어서 실제 PNG 파일인지 확인 (PNG 시그니처: 89 50 4E 47)
                    try {
                        byte[] header = readHead(path, 4);
                        if (header.length >= 4 && header[0] == (byte) 0x89 && header[1] == 0x50 &&
                                header[2] == 0x4E && header[3] == 0x47) {
                            return ok(MSG_SUCCESS, mime, null, null);
                        }
                    } catch (Exception e) {
                        // 헤더 읽기 실패 시 MIME 타입 불일치로 간주
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".jpg":
            case ".jpeg":
                // JPEG는 image/jpeg MIME 타입이어야 함
                if (!mimeLower.contains("image/jpeg")) {
                    // 헤더만 읽어서 실제 JPEG 파일인지 확인 (JPEG 시그니처: FF D8 FF)
                    try {
                        byte[] header = readHead(path, 3);
                        if (header.length >= 3 && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 &&
                                header[2] == (byte) 0xFF) {
                            return ok(MSG_SUCCESS, mime, null, null);
                        }
                    } catch (Exception e) {
                        // 헤더 읽기 실패 시 MIME 타입 불일치로 간주
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".txt":
                // TXT는 text/plain MIME 타입이어야 함
                if (!mimeLower.contains("text/plain") && !mimeLower.contains("text")) {
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".zip":
                // ZIP은 application/zip MIME 타입이어야 함
                if (!mimeLower.contains("zip")) {
                    // 헤더만 읽어서 실제 ZIP 파일인지 확인 (ZIP 시그니처: PK 03 04)
                    try {
                        byte[] header = readHead(path, 4);
                        if (header.length >= 4 && header[0] == 0x50 && header[1] == 0x4B &&
                                header[2] == 0x03 && header[3] == 0x04) {
                            return ok(MSG_SUCCESS, mime, null, null);
                        }
                    } catch (Exception e) {
                        // 헤더 읽기 실패 시 MIME 타입 불일치로 간주
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            case ".xml":
                // XML은 application/xml 또는 text/xml MIME 타입이어야 함
                if (!mimeLower.contains("xml")) {
                    // 헤더만 읽어서 실제 XML 파일인지 확인 (XML 선언: <?xml)
                    try {
                        byte[] header = readHead(path, 5);
                        String headerStr = new String(header, StandardCharsets.UTF_8).trim();
                        if (headerStr.startsWith("<?xml") || headerStr.startsWith("<")) {
                            return ok(MSG_SUCCESS, mime, null, null);
                        }
                    } catch (Exception e) {
                        // 헤더 읽기 실패 시 MIME 타입 불일치로 간주
                    }
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime);
                }
                break;

            default:
                // 허용된 확장자인데 switch에 없는 경우는 개발 오류이지만, 안전을 위해 MIME 타입 불일치로 처리
                // 허용되지 않은 확장자는 이미 앞에서 걸러졌으므로 여기 도달하면 안 됨
                if (ALLOWED_EXTENSIONS.contains(extLower)) {
                    // 허용된 확장자인데 검증 로직이 없는 경우 - 개발 오류
                    // 안전을 위해 MIME 타입 불일치로 처리
                    return fail(ERR_EXTENSION_MIME_MISMATCH + extension + ", 감지된 MIME: " + mime + " (검증 로직 누락)");
                }
                // 허용되지 않은 확장자는 이미 앞에서 걸러졌으므로 여기 도달하면 안 됨
                break;
        }

        return ok(MSG_SUCCESS, mime, null, null);
    }

    /**
     * 파일 헤더만 읽어서 MIME 타입을 감지한다.
     */
    private static String detectMimeFromHeader(Path path, String originalName) throws IOException {
        // 파일 헤더만 읽기 (최대 HEADER_READ_BYTES 바이트)
        byte[] header = readHead(path, HEADER_READ_BYTES);
        if (header.length == 0) {
            throw new IOException(ERR_DETAIL_FILE_READ);
        }

        DefaultDetector detector = new DefaultDetector();
        Metadata metadata = new Metadata();
        if (originalName != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalName);
        }

        // 헤더 바이트만으로 MIME 타입 감지 (ByteArrayInputStream 사용)
        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(header);
             TikaInputStream stream = TikaInputStream.get(byteStream)) {
            MediaType mediaType = detector.detect(stream, metadata);
            String mime = mediaType.toString();
            if (MIME_TIKA_OOXML.equalsIgnoreCase(mime)) {
                return refineOoxmlMime(header, mime);
            }
            return mime;
        }
    }

    /**
     * 확장자와 MIME 타입만으로 파일 타입을 결정합니다 (path 없이).
     * InputStream 기반 검증에서 사용됩니다.
     */
    private static FileType determineFileTypeFromExtensionAndMime(String mime, String extension) {
        if (mime == null) {
            return null;
        }

        String mimeLower = mime.toLowerCase();
        String extLower = extension != null ? extension.toLowerCase() : "";

        // Excel
        if (mimeLower.contains("spreadsheet") || mimeLower.contains("ms-excel") ||
                EXT_XLS.equalsIgnoreCase(extLower) || EXT_XLSX.equalsIgnoreCase(extLower)) {
            return FileType.EXCEL;
        }

        // CSV
        if (mimeLower.contains("csv") || EXT_CSV.equalsIgnoreCase(extLower)) {
            return FileType.CSV;
        }

        // Word 문서
        if (mimeLower.contains("wordprocessingml") || mimeLower.contains("msword") ||
                extLower.equals(".doc") || extLower.equals(".docx")) {
            return FileType.DOCUMENT;
        }

        // PowerPoint
        if (mimeLower.contains("presentationml") || mimeLower.contains("ms-powerpoint") ||
                extLower.equals(".ppt") || extLower.equals(".pptx")) {
            return FileType.PRESENTATION;
        }

        // PDF
        if (mimeLower.contains("pdf") || extLower.equals(".pdf")) {
            return FileType.PDF;
        }

        // 이미지
        if (mimeLower.contains("image/png") || extLower.equals(".png")) {
            return FileType.IMAGE;
        }
        if (mimeLower.contains("image/jpeg") || extLower.equals(".jpg") || extLower.equals(".jpeg")) {
            return FileType.IMAGE;
        }

        // 텍스트
        if (mimeLower.contains("text/plain") || extLower.equals(".txt")) {
            return FileType.TEXT;
        }

        // ZIP
        if (mimeLower.contains("zip") || extLower.equals(".zip")) {
            return FileType.ARCHIVE;
        }

        // XML
        if (mimeLower.contains("xml") || extLower.equals(".xml")) {
            return FileType.XML;
        }

        return FileType.OTHER;
    }

    private static FileType determineFileType(String mime, Path path, String originalName) {
        if (mime == null) {
            return null;
        }

        String mimeLower = mime.toLowerCase();
        String extension = extractExtension(originalName).toLowerCase();

        // Excel
        if (mimeLower.contains("spreadsheet") || mimeLower.contains("ms-excel") ||
                EXT_XLS.equalsIgnoreCase(extension) || EXT_XLSX.equalsIgnoreCase(extension)) {
            return FileType.EXCEL;
        }

        // CSV
        if (mimeLower.contains("csv") || EXT_CSV.equalsIgnoreCase(extension) ||
                (path != null && looksLikeCsv(path))) {
            return FileType.CSV;
        }

        // Word 문서
        if (mimeLower.contains("wordprocessingml") || mimeLower.contains("msword") ||
                extension.equals(".doc") || extension.equals(".docx")) {
            return FileType.DOCUMENT;
        }

        // PowerPoint
        if (mimeLower.contains("presentationml") || mimeLower.contains("ms-powerpoint") ||
                extension.equals(".ppt") || extension.equals(".pptx")) {
            return FileType.PRESENTATION;
        }

        // PDF
        if (mimeLower.contains("pdf") || extension.equals(".pdf")) {
            return FileType.PDF;
        }

        // 이미지
        if (mimeLower.contains("image/png") || extension.equals(".png")) {
            return FileType.IMAGE;
        }
        if (mimeLower.contains("image/jpeg") || extension.equals(".jpg") || extension.equals(".jpeg")) {
            return FileType.IMAGE;
        }

        // 텍스트
        if (mimeLower.contains("text/plain") || extension.equals(".txt")) {
            return FileType.TEXT;
        }

        // ZIP
        if (mimeLower.contains("zip") || extension.equals(".zip")) {
            return FileType.ARCHIVE;
        }

        // XML
        if (mimeLower.contains("xml") || extension.equals(".xml")) {
            return FileType.XML;
        }

        return FileType.OTHER;
    }

    private static FileCheckResult validateCsv(Path path, String mime) {
        // 확장자가 .csv인 경우, 실제 파일 내용이 CSV 형식인지 확인
        if (!looksLikeCsv(path)) {
            return fail(ERR_CSV_FORMAT_INVALID);
        }

        // 헤더만 읽어서 인코딩 감지
        String detected = detectCharset(path, CHARSET_SAMPLE_BYTES);
        String normalized = normalizeUtf8(detected, path);
        if (normalized == null) {
            return fail(ERR_CSV_ENCODING_UNKNOWN);
        }
        if (!normalized.equalsIgnoreCase(ENC_UTF8) && !normalized.equalsIgnoreCase(ENC_UTF8_SIG)) {
            return fail(ERR_CSV_ENCODING_INVALID + detected + ERR_SUFFIX_CLOSE_PAREN);
        }
        // 헤더만 읽어서 빈 파일 체크
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            if (reader.readLine() == null) {
                return fail(ERR_CSV_EMPTY);
            }
        } catch (IOException e) {
            return fail(ERR_FILE_PROCESS + e.getMessage());
        }
        return ok(MSG_SUCCESS_CSV, mime, FileType.CSV, normalized);
    }

    private static FileCheckResult validateExcel(Path path, String mime, String originalName) {
        String extension = extractExtension(originalName);
        try {
            // 헤더만 읽어서 Excel 파일 유효성 검증
            if (EXT_XLSX.equalsIgnoreCase(extension)) {
                validateXlsxHeader(path);
            } else if (EXT_XLS.equalsIgnoreCase(extension)) {
                validateXlsHeader(path);
            } else {
                // 확장자가 없거나 다른 경우 헤더만으로 검증
                validateExcelHeader(path);
            }

            // 인코딩 체크는 필요시 추가 가능 (현재는 MIME 타입만 확인)
            return ok(MSG_SUCCESS_EXCEL, mime, FileType.EXCEL, null);
        } catch (IOException e) {
            return fail(ERR_FILE_PROCESS + e.getMessage());
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
    }

    /**
     * XLSX 파일 헤더만 읽어서 유효성 검증 (대용량 파일 지원).
     */
    private static void validateXlsxHeader(Path path) throws IOException {
        // XLSX는 ZIP 기반이므로 헤더만 읽어서 ZIP 시그니처 확인
        byte[] header = readHead(path, 4);
        if (header.length < 4) {
            throw new IOException(ERR_XLSX_INVALID + ERR_DETAIL_FILE_TOO_SMALL);
        }
        // ZIP 파일 시그니처 확인 (PK\x03\x04)
        if (header[0] != 0x50 || header[1] != 0x4B || header[2] != 0x03 || header[3] != 0x04) {
            throw new IOException(ERR_XLSX_INVALID + ERR_DETAIL_INVALID_XLSX);
        }

        // Content_Types.xml 확인을 위해 더 읽기 (헤더 범위 내, 대용량 파일 지원)
        // ZIP 파일의 Local File Header는 파일 시작 부분에 있으므로 헤더만 읽어서 확인 가능
        byte[] moreHeader = readHead(path, 8192); // 충분한 크기의 헤더 읽기
        String headerStr = new String(moreHeader, StandardCharsets.ISO_8859_1);

        // OOXML 관련 문자열이 헤더에 있는지 확인
        // XLSX 파일의 경우 [Content_Types].xml이나 spreadsheetml.sheet.main+xml이 헤더 부분에 포함됨
        if (!headerStr.contains(OOXML_CONTENT_TYPES_XML) && !headerStr.contains(OOXML_SPREADSHEET_MAIN)) {
            // 헤더에서 찾지 못한 경우, ZIP Local File Header 구조를 확인
            // ZIP Local File Header는 최소 30바이트이므로 더 읽어서 확인
            if (moreHeader.length < 30) {
                throw new IOException(ERR_XLSX_INVALID + ERR_DETAIL_FILE_TOO_SMALL);
            }

            // ZIP Local File Header의 파일명 길이 확인
            // 파일명 길이는 offset 26-27에 있음
            int fileNameLength = (moreHeader[26] & 0xFF) | ((moreHeader[27] & 0xFF) << 8);

            // 파일명이 있는 위치 확인 (offset 30부터)
            if (moreHeader.length >= 30 + fileNameLength) {
                String fileName = new String(moreHeader, 30, fileNameLength, StandardCharsets.ISO_8859_1);
                // [Content_Types].xml 파일명이 있는지 확인
                if (fileName.contains(OOXML_CONTENT_TYPES_XML) || fileName.contains("xl/")) {
                    // XLSX 파일로 판단
                    return;
                }
            }

            // 헤더만으로는 확실히 판단할 수 없지만, ZIP 시그니처는 맞으므로 경고만
            // 실제 파일 내용 검증은 사용 시점에 수행하도록 함
            throw new IOException(ERR_XLSX_INVALID + "헤더에서 XLSX 형식을 확인할 수 없습니다.");
        }
    }

    /**
     * XLS 파일 헤더만 읽어서 유효성 검증.
     */
    private static void validateXlsHeader(Path path) throws IOException {
        // XLS는 OLE2 포맷이므로 헤더만 읽어서 시그니처 확인
        byte[] header = readHead(path, 8);
        if (header.length < 8) {
            throw new IOException(ERR_XLS_INVALID + ERR_DETAIL_FILE_TOO_SMALL);
        }
        // OLE2 시그니처 확인 (0xD0CF11E0A1B11AE1)
        if (header[0] != (byte) 0xD0 || header[1] != (byte) 0xCF ||
                header[2] != 0x11 || header[3] != (byte) 0xE0) {
            throw new IOException(ERR_XLS_INVALID + ERR_DETAIL_INVALID_XLS);
        }
    }

    /**
     * Excel 파일 헤더만 읽어서 유효성 검증 (fallback).
     */
    private static void validateExcelHeader(Path path) throws IOException {
        // WorkbookFactory는 내부적으로 헤더를 읽어서 검증
        try (InputStream input = Files.newInputStream(path)) {
            // 최소한의 헤더만 읽어서 검증
            byte[] header = input.readNBytes(8);
            if (header.length < 8) {
                throw new IOException(ERR_EXCEL_INVALID + ERR_DETAIL_FILE_TOO_SMALL);
            }
            // XLSX (ZIP) 또는 XLS (OLE2) 시그니처 확인
            boolean isZip = header[0] == 0x50 && header[1] == 0x4B;
            boolean isOle2 = header[0] == (byte) 0xD0 && header[1] == (byte) 0xCF;
            if (!isZip && !isOle2) {
                throw new IOException(ERR_EXCEL_INVALID + ERR_DETAIL_INVALID_EXCEL);
            }
        }
    }

    /**
     * OOXML 파일의 MIME 타입을 헤더에서 정제.
     */
    private static String refineOoxmlMime(byte[] header, String fallbackMime) {
        try {
            // 헤더에서 ZIP 엔트리 찾기
            String headerStr = new String(header, StandardCharsets.ISO_8859_1);
            if (headerStr.contains(OOXML_SPREADSHEET_MAIN)) {
                return MIME_XLSX;
            }
            if (headerStr.contains(OOXML_WORD_MAIN)) {
                return MIME_WORD;
            }
            if (headerStr.contains(OOXML_PRESENTATION_MAIN)) {
                return MIME_PRESENTATION;
            }
        } catch (Exception refineError) {
            throw new IllegalStateException(ERR_DETAIL_OOXML_REFINE, refineError);
        }
        return fallbackMime;
    }

    private static boolean looksLikeCsv(Path path) {
        byte[] head = readHead(path, CSV_SNIFF_BYTES);
        String sample = new String(stripBomIfNecessary(head), StandardCharsets.ISO_8859_1);
        boolean hasDelimiter = sample.contains(",") || sample.contains(";") || sample.contains("\t");
        boolean hasNewLine = sample.contains("\n") || sample.contains("\r");
        return hasDelimiter && hasNewLine;
    }

    private static byte[] readHead(Path path, int size) {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(size);
        } catch (IOException e) {
            throw new IllegalStateException(ERR_DETAIL_HEADER_READ + path, e);
        }
    }

    private static String extractExtension(String originalName) {
        if (originalName == null) {
            return "";
        }
        int index = originalName.lastIndexOf('.');
        return index >= 0 ? originalName.substring(index) : "";
    }

    /**
     * 파일 헤더만 읽어서 인코딩 감지.
     */
    private static String detectCharset(Path path, int maxBytes) {
        try {
            // 파일의 헤더 부분만 읽어서 인코딩 감지
            byte[] sample = readHead(path, maxBytes);
            if (sample.length == 0) {
                return null;
            }

            // ICU4J CharsetDetector 사용
            CharsetDetector detector = new CharsetDetector();
            detector.setText(sample);
            CharsetMatch match = detector.detect();

            if (match != null && match.getConfidence() > 0) {
                return match.getName();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeUtf8(String detected, Path path) {
        try {
            byte[] head = readHead(path, 4);
            if (hasUtf8Bom(head)) {
                return ENC_UTF8_SIG;
            }
            if (detected == null) {
                return null;
            }
            if (detected.equalsIgnoreCase(ENC_UTF8) || detected.equalsIgnoreCase(ENC_UTF8_SIG)) {
                return detected.toUpperCase();
            }
            // UTF-8로 디코딩 가능한지 헤더만 확인
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                char[] buffer = new char[COPY_BUFFER_SIZE];
                reader.read(buffer); // 헤더만 읽기
            }
            return ENC_UTF8;
        } catch (Exception e) {
            return detected;
        }
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF;
    }

    private static byte[] stripBomIfNecessary(byte[] bytes) {
        return hasUtf8Bom(bytes) ? Arrays.copyOfRange(bytes, 3, bytes.length) : bytes;
    }

    private static FileCheckResult ok(String message, String mime, FileType type, String encoding) {
        return new FileCheckResult(true, message, mime, type, encoding);
    }

    private static FileCheckResult fail(String message) {
        return new FileCheckResult(false, message, null, null, null);
    }
}
