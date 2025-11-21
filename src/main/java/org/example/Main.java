package org.example;

import com.skax.aiplatform.common.util.MultipartFileHeaderChecker;
import org.apache.tika.Tika;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;

/**
 * MultipartFileHeaderChecker를 사용한 파일 검증 테스트 프로그램
 */
public class Main {

    public static void main(String[] args) throws IOException {
        // 테스트 파일 목록
        final String filepath = "/Users/krekar83/workspace/doc_samples/";
        final String[] utf8CheckFiles = {
                "o_csv_small_euckr.csv",
                "o_csv_small_utf8.csv",
                "x_csv_large.csv" // 2GB!!!
        };

        final String[] generalFiles = {
                "o_excel_small_utf8.xlsx",
                "o_doc.doc",
                "o_docx.docx",
                "o_jpeg.jpg",
                "o_jpg.jpg",
                "o_pdf.pdf",
                "o_png.png",
                "o_ppt.ppt",
                "o_pptx.pptx",
                "o_txt.txt",
                "o_xls.xls",
                "o_xlsx.xlsx",
                "o_xml.xml",
                "o_zip.zip",
                "x_excel_small_utf8.xlsx",
                "x_doc.doc",
                "x_docx.docx",
                "x_jpeg.jpg",
                "x_jpg.jpg",
                "x_pdf.pdf",
                "x_png.png",
                "x_ppt.ppt",
                "x_pptx.pptx",
                "x_txt.txt",
                "x_xls.xls",
                "x_xlsx.xlsx",
                "x_xml.xml",
                "x_zip.zip"
        };

        final int totalFiles = utf8CheckFiles.length + generalFiles.length;

        System.out.println("=".repeat(80));
        System.out.println("파일 검증 테스트 시작");
        System.out.println("=".repeat(80));
        System.out.println();

        int processed = 0;
        System.out.println("[UTF-8 인코딩 검증 세트]");
        System.out.printf("파일 개수: %d개%n", utf8CheckFiles.length);
        System.out.println();
        processed = runValidationSet(filepath, utf8CheckFiles, true, processed, totalFiles);
        
        System.out.println();
        System.out.println("[일반 검증 세트]");
        System.out.printf("파일 개수: %d개%n", generalFiles.length);
        System.out.println();
        runValidationSet(filepath, generalFiles, false, processed, totalFiles);

        System.out.println("=".repeat(80));
        System.out.println("파일 검증 테스트 완료");
        System.out.println("=".repeat(80));
    }

    private static int runValidationSet(String basePath,
                                        String[] filenames,
                                        boolean checkUtf8Encoding,
                                        int processedSoFar,
                                        int totalFiles) throws IOException {
        for (int i = 0; i < filenames.length; i++) {
            long startTime = System.nanoTime();
            String filename = filenames[i];
            int fileNumber = processedSoFar + i + 1;

            System.out.println("-".repeat(80));
            System.out.printf("[%d/%d] 파일 검증 시작: %s%n", fileNumber, totalFiles, filename);
            System.out.println("-".repeat(80));

            try {
                // 테스트 파일 세팅
                File file = new File(basePath + filename);
                if (!file.exists()) {
                    System.out.printf("⚠️  파일을 찾을 수 없습니다: %s%n", file.getAbsolutePath());
                    System.out.println();
                    continue;
                }

                java.nio.file.Path filePath = file.toPath();
                long fileSize = Files.size(filePath);
                String mimeType = Files.probeContentType(filePath);
                if (mimeType == null) {
                    mimeType = new Tika().detect(file);
                }

                System.out.printf("📁 파일 정보:%n");
                System.out.printf("   - 파일명: %s%n", file.getName());
                System.out.printf("   - 파일 크기: %,d bytes (%.2f MB)%n",
                        fileSize, fileSize / (1024.0 * 1024.0));
                System.out.printf("   - MIME 타입: %s%n", mimeType);
                System.out.println();

                // CSV / EXCEL 파일 검증 부분 (실제 Controller에 적용해야 할 코드 샘플)
                // 스트리밍 방식의 MultipartFile 생성 (메모리 효율적, 큰 파일도 처리 가능)
                // 주의: 큰 파일(>100MB)의 경우 StreamingMultipartFile을 사용해야 합니다.
                // MockMultipartFile은 파일 전체를 메모리에 로드하므로 OOM이 발생할 수 있습니다.
                MultipartFile multipartFile = new StreamingMultipartFile(file, mimeType);

                long validationStartTime = System.nanoTime();
                // 실제 생성형AI플랫폼 파일 업로드 소스에 붙여야 하는 부분
                // 허용 확장자 검증 (ppt, pptx, pdf, doc, docx, xls, xlsx, png, jpg, jpeg, txt, zip, csv, xml)
                // CSV -> mimeType 과 encoding 검증 (utf8 인코딩 여부)
                // EXCEL -> mimeType 과 헤더 검증 (xlsx, xls)
                // DOCUMENT -> mimeType 과 헤더 검증 (doc, docx)
                // PRESENTATION -> mimeType 과 헤더 검증 (ppt, pptx)
                // PDF -> mimeType 과 헤더 검증 (pdf)
                // IMAGE -> mimeType 과 헤더 검증 (png, jpg, jpeg)
                // TEXT -> mimeType 과 헤더 검증 (txt)
                // ARCHIVE -> mimeType 과 헤더 검증 (zip)
                // XML -> mimeType 과 헤더 검증 (xml)
                // OTHER -> mimeType 과 헤더 검증 (기타)
                // 허용 확장자 검증 완료 후 검증 진행
                MultipartFileHeaderChecker.FileCheckResult result = checkUtf8Encoding
                        ? MultipartFileHeaderChecker.validate(multipartFile, true)
                        : MultipartFileHeaderChecker.validate(multipartFile);
                long validationEndTime = System.nanoTime();
                double validationTimeMs = (validationEndTime - validationStartTime) / 1_000_000.0;

                // 검증 결과 출력
                System.out.printf("🔍 검증 결과:%n");
                try {
                    Class<?> recordClass = result.getClass();
                    if (recordClass.isRecord()) {
                        RecordComponent[] components = recordClass.getRecordComponents();
                        for (RecordComponent component : components) {
                            try {
                                var value = component.getAccessor().invoke(result);
                                String fieldName = component.getName();
                                String displayValue = formatValue(fieldName, value);
                                System.out.printf("   - %s: %s%n", fieldName, displayValue);
                            } catch (Exception e) {
                                System.out.printf("   - %s: <값 조회 실패: %s>%n", component.getName(), e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.printf("   - 검증 결과 출력 중 오류: %s%n", e.getMessage());
                    e.printStackTrace();
                }
                System.out.println();
                System.out.flush(); // 검증 결과 출력 후 버퍼 강제 출력

                // 검증 상태 및 실행 시간
                long endTime = System.nanoTime();
                double totalTimeMs = (endTime - startTime) / 1_000_000.0;

                if (result.ok()) {
                    System.out.printf("✅ 검증 성공! (검증 시간: %.2f ms, 전체 시간: %.2f ms)%n",
                            validationTimeMs, totalTimeMs);
                } else {
                    System.out.printf("❌ 검증 실패: %s (검증 시간: %.2f ms, 전체 시간: %.2f ms)%n",
                            result.message(), validationTimeMs, totalTimeMs);
                }

            } catch (Exception e) {
                long endTime = System.nanoTime();
                double totalTimeMs = (endTime - startTime) / 1_000_000.0;
                System.out.printf("❌ 오류 발생: %s (실행 시간: %.2f ms)%n", e.getMessage(), totalTimeMs);
                System.out.println("오류 상세:");
                e.printStackTrace();
                System.out.flush(); // 버퍼 강제 출력
            }

            System.out.println();
            System.out.flush(); // 각 파일 처리 후 버퍼 강제 출력
        }

        return processedSoFar + filenames.length;
    }

    private static String formatValue(String fieldName, Object value) {
        if (value == null) {
            return "<null>";
        }

        if ("ok".equals(fieldName)) {
            return (Boolean) value ? "✓" : "✗";
        }

        if (value instanceof Boolean) {
            return value.toString();
        }

        return value.toString();
    }

    /**
     * 스트리밍 방식의 MultipartFile 구현체.
     * 파일을 메모리에 로드하지 않고 스트리밍으로 처리한다.
     */
    private static class StreamingMultipartFile implements MultipartFile {
        private final File file;
        private final String contentType;

        public StreamingMultipartFile(File file, String contentType) {
            this.file = file;
            this.contentType = contentType;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return file.getName();
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return file.length() == 0;
        }

        @Override
        public long getSize() {
            return file.length();
        }

        @Override
        public byte[] getBytes() throws IOException {
            // 큰 파일의 경우 메모리 문제 방지를 위해 예외 발생
            // MultipartFileHeaderChecker는 getInputStream()만 사용하므로 이 메서드는 호출되지 않음
            long fileSize = file.length();
            if (fileSize > 100 * 1024 * 1024) { // 100MB 이상
                throw new IOException(
                        String.format("큰 파일(%d bytes)은 getBytes()로 읽을 수 없습니다. getInputStream()을 사용하세요.", fileSize)
                );
            }
            return Files.readAllBytes(file.toPath());
        }

        @Override
        public InputStream getInputStream() throws IOException {
            // 스트리밍 방식으로 파일을 읽음 (메모리 효율적)
            return new FileInputStream(file);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
            Files.copy(file.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
