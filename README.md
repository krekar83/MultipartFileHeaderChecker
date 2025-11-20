# MultipartFileHeaderChecker

업로드된 파일의 헤더를 읽어 MIME 타입과 인코딩을 검증하는 Java 유틸리티 라이브러리입니다.

## 주요 기능

- **파일 확장자 검증**: 허용된 파일 확장자 목록에 포함되어 있는지 확인
- **MIME 타입 검증**: 파일 헤더만 읽어서 실제 MIME 타입 확인 (메모리 효율적)
- **인코딩 검증**: CSV 파일의 UTF-8 인코딩 검증 (선택적)
- **다양한 파일 타입 지원**: CSV, Excel, Word, PowerPoint, PDF, 이미지, 텍스트, ZIP, XML 등

## 지원 파일 타입

- **문서**: `.doc`, `.docx`, `.pdf`
- **스프레드시트**: `.xls`, `.xlsx`, `.csv`
- **프레젠테이션**: `.ppt`, `.pptx`
- **이미지**: `.png`, `.jpg`, `.jpeg`
- **기타**: `.txt`, `.zip`, `.xml`

## 요구사항

- Java 17 이상
- Maven 3.6 이상

## 의존성

- Spring Boot 3.5.4
- Apache POI 5.4.1 (Excel 파일 처리)
- Apache Tika 2.9.4 (MIME 타입 감지)
- ICU4J 58.1 (인코딩 감지)

## 사용 방법

### 기본 사용

```java
import com.skax.aiplatform.common.util.MultipartFileHeaderChecker;
import org.springframework.web.multipart.MultipartFile;

// 기본 검증 (MIME 타입만 확인)
MultipartFileHeaderChecker.FileCheckResult result = 
    MultipartFileHeaderChecker.validate(multipartFile);

if (result.ok()) {
    System.out.println("검증 성공: " + result.mimeType());
} else {
    System.out.println("검증 실패: " + result.message());
}
```

### UTF-8 인코딩 검증 포함 (CSV 파일)

```java
// CSV 파일의 UTF-8 인코딩 검증 포함
MultipartFileHeaderChecker.FileCheckResult result = 
    MultipartFileHeaderChecker.validate(multipartFile, true);

if (result.ok()) {
    System.out.println("검증 성공");
    System.out.println("MIME 타입: " + result.mimeType());
    System.out.println("인코딩: " + result.encoding());
} else {
    System.out.println("검증 실패: " + result.message());
}
```

### 파일 경로로 직접 검증

```java
import java.nio.file.Path;
import java.nio.file.Paths;

Path filePath = Paths.get("/path/to/file.csv");
String originalName = "file.csv";

MultipartFileHeaderChecker.FileCheckResult result = 
    MultipartFileHeaderChecker.validate(filePath, originalName);
```

## 검증 결과

`FileCheckResult` 레코드는 다음 정보를 포함합니다:

- `ok`: 검증 성공 여부 (boolean)
- `message`: 검증 결과 메시지 (String)
- `mimeType`: 감지된 MIME 타입 (String)
- `fileType`: 파일 타입 열거형 (FileType)
- `encoding`: 감지된 인코딩 (String, CSV 파일인 경우)

## 특징

- **메모리 효율적**: 파일 헤더만 읽어서 검증하므로 대용량 파일도 안전하게 처리
- **빠른 검증**: 전체 파일을 읽지 않고 헤더만 확인하여 빠른 검증 가능
- **정확한 MIME 타입 감지**: Apache Tika를 사용하여 실제 파일 내용 기반 MIME 타입 확인

## 빌드

```bash
mvn clean install
```

## 테스트

프로젝트에 포함된 `Main.java`를 실행하여 샘플 파일 검증을 테스트할 수 있습니다.

```bash
mvn exec:java -Dexec.mainClass="org.example.Main"
```

## 라이선스

이 프로젝트는 MIT 라이선스를 따릅니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.

## 기여

이슈 및 풀 리퀘스트를 환영합니다.

