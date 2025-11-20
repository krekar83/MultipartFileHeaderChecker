package com.skax.aiplatform.common.util;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * 업로드된 파일의 헤더를 읽어 MIME 타입과 인코딩을 검증하는 유틸리티.
 */
public final class MultipartFileHeaderChecker {

    // 헤더 읽기 크기 (파일 헤더만 읽기 위해 충분한 크기)
    private static final int HEADER_READ_BYTES = 8192;
    private static final int CSV_SNIFF_BYTES = 4096;
    private static final int CHARSET_SAMPLE_BYTES = 8192; // 헤더만 읽도록 변경
    private static final int COPY_BUFFER_SIZE = 8192;
    private static final String TEMP_FILE_PREFIX = "upload-";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    // 허용 파일 확장자 목록
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".ppt", ".pptx", ".pdf", ".doc", ".docx",
            ".xls", ".xlsx", ".png", ".jpg", ".jpeg",
            ".txt", ".zip", ".csv", ".xml"
    );

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
    private static final String ERR_XLSX_INVALID = "XLSX 포맷 오류: ";
    private static final String ERR_XLSX_NO_SHEET = "XLSX 시트를 찾을 수 없습니다.";
    private static final String ERR_XLS_INVALID = "XLS 포맷 오류: ";
    private static final String ERR_EXCEL_INVALID = "Excel 포맷 오류: ";
    private static final String ERR_DETAIL_NO_FILENAME = "파일명이 없습니다.";
    private static final String ERR_DETAIL_FILE_TOO_SMALL = "파일이 너무 작습니다.";
    private static final String ERR_DETAIL_INVALID_XLSX = "올바른 XLSX 파일이 아닙니다.";
    private static final String ERR_DETAIL_INVALID_XLS = "올바른 XLS 파일이 아닙니다.";
    private static final String ERR_DETAIL_INVALID_EXCEL = "올바른 Excel 파일이 아닙니다.";
    private static final String ERR_DETAIL_FILE_READ = "파일을 읽을 수 없습니다.";
    private static final String ERR_SUFFIX_CLOSE_PAREN = ")";
    private static final String ERR_DETAIL_TEMP_DELETE = "임시 파일 삭제에 실패했습니다: ";
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
     *
     * @param file 검증할 파일
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

        Path temp = null;
        try {
            temp = copyToTemp(file);
            return validatePath(temp, originalName, true, checkUTF8Encoding);
        } catch (IOException e) {
            return fail(ERR_FILE_PROCESS + e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException cleanupError) {
                    throw new IllegalStateException(ERR_DETAIL_TEMP_DELETE + temp, cleanupError);
                }
            }
        }
    }

    /**
     * 파일 경로로 직접 파일 헤더를 검증한다.
     * 큰 파일을 처리할 때 메모리 효율적이다.
     *
     * @param filePath 검증할 파일의 경로
     * @param originalName 원본 파일명 (확장자 판별을 위해 사용)
     * @param deleteAfterValidation 검증 후 파일을 삭제할지 여부
     * @return 검증 결과
     */
    public static FileCheckResult validate(Path filePath, String originalName, boolean deleteAfterValidation) {
        return validate(filePath, originalName, deleteAfterValidation, false);
    }

    /**
     * 파일 경로로 직접 파일 헤더를 검증한다.
     *
     * @param filePath 검증할 파일의 경로
     * @param originalName 원본 파일명 (확장자 판별을 위해 사용)
     * @param deleteAfterValidation 검증 후 파일을 삭제할지 여부
     * @param checkUTF8Encoding CSV/XLS/XLSX 파일의 UTF-8 인코딩 검증 여부
     * @return 검증 결과
     */
    public static FileCheckResult validate(Path filePath, String originalName, boolean deleteAfterValidation, boolean checkUTF8Encoding) {
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
     * @param filePath 검증할 파일의 경로
     * @param originalName 원본 파일명 (확장자 판별을 위해 사용)
     * @return 검증 결과
     */
    public static FileCheckResult validate(Path filePath, String originalName) {
        return validate(filePath, originalName, false, false);
    }

    /**
     * 내부 검증 로직 (공통).
     */
    private static FileCheckResult validatePath(Path path, String originalName, boolean deleteAfterValidation, boolean checkUTF8Encoding) throws IOException {
        try {
            // 파일 헤더만 읽어서 MIME 타입 감지
            String mime = detectMimeFromHeader(path, originalName);
            FileType fileType = determineFileType(mime, path, originalName);
            if (fileType == null) {
                return fail(ERR_UNSUPPORTED_TYPE + mime + ERR_SUFFIX_CLOSE_PAREN);
            }

            // CSV/XLS/XLSX 파일이고 인코딩 체크가 필요한 경우
            if (checkUTF8Encoding) {
                String extension = extractExtension(originalName);
                if (EXT_CSV.equalsIgnoreCase(extension)) {
                    return validateCsv(path, mime);
                } else if (EXT_XLS.equalsIgnoreCase(extension) || EXT_XLSX.equalsIgnoreCase(extension)) {
                    return validateExcel(path, mime, originalName);
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

    private static Path copyToTemp(MultipartFile file) throws IOException {
        String suffix = Optional.ofNullable(file.getOriginalFilename())
                .filter(name -> name.contains("."))
                .map(name -> name.substring(name.lastIndexOf('.')))
                .orElse("");

        // 시분초밀리세컨드 타임스탬프를 포함한 유니크한 파일명 생성
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        String fileName = TEMP_FILE_PREFIX + timestamp + suffix;
        Path temp = Files.createTempFile(fileName, null);

        // 스트리밍 방식으로 복사 (메모리 효율적, 큰 파일도 처리 가능)
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return temp;
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
        if (mimeLower.contains("csv") || EXT_CSV.equalsIgnoreCase(extension) || looksLikeCsv(path)) {
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
     * XLSX 파일 헤더만 읽어서 유효성 검증.
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

        // Content_Types.xml 확인을 위해 더 읽기 (헤더 범위 내)
        byte[] moreHeader = readHead(path, 1024);
        String headerStr = new String(moreHeader, StandardCharsets.ISO_8859_1);
        if (!headerStr.contains(OOXML_CONTENT_TYPES_XML) && !headerStr.contains(OOXML_SPREADSHEET_MAIN)) {
            // 헤더에서 찾지 못하면 전체 파일을 열어서 확인 (최소한의 검증)
            try (OPCPackage pkg = OPCPackage.open(path.toFile(), PackageAccess.READ)) {
                XSSFReader reader = new XSSFReader(pkg);
                var iterator = reader.getSheetsData();
                if (!iterator.hasNext()) {
                    throw new IOException(ERR_XLSX_INVALID + ERR_XLSX_NO_SHEET);
                }
            } catch (Exception e) {
                throw new IOException(ERR_XLSX_INVALID + e.getMessage(), e);
            }
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
