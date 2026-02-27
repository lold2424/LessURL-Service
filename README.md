# LessURL

## 🦁 멋쟁이사자 인턴쉽 프로젝트: 서버리스 URL 단축 서비스

LessURL은 AWS 서버리스 아키텍처(Lambda, API Gateway, DynamoDB)를 기반으로 구축된 고성능 URL 단축 서비스입니다. 단순한 URL 연결을 넘어 **SnapStart를 활용한 응답 속도 최적화**, **SQS를 이용한 비동기 통계 분석**, 그리고 **Gemini AI를 통한 트렌드 분석** 기능을 제공합니다.

---

### 🚀 핵심 기능 (Key Features)

-   **고성능 URL 단축:** 긴 URL을 짧고 고유한 ID로 신속하게 변환.
-   **실시간 리디렉션 & 비동기 로깅:** 사용자가 단축 URL 클릭 시 즉각적인 원본 이동과 동시에 SQS를 통한 비동기 데이터 분석 수행.
-   **보안 검사:** Google Safe Browsing API를 연동하여 위험한 URL 등록을 사전에 차단.
-   **AI 인사이트:** Gemini AI를 활용해 특정 URL의 유입 패턴과 인기 트렌드를 분석하여 리포트 제공.
-   **상세 통계:** 시간대별, 일별, 레퍼러(Referer)별 클릭 지표 대시보드.
-   **모니터링 & 알람:** CloudWatch Alarms와 SNS를 통해 장애 발생 시 즉각적인 이메일 알림 발송.

---

### 🛠 기술 스택 (Tech Stack)

| 구분 | 기술 | 비고 |
| :--- | :--- | :--- |
| **Compute** | **AWS Lambda** | Java 21 기반 서버리스 함수 (SnapStart 적용) |
| **API** | **API Gateway** | RESTful API 엔드포인트 제공 |
| **Database** | **DynamoDB** | NoSQL 데이터베이스 (5개 테이블, GSI 활용) |
| **Messaging** | **AWS SQS** | 리디렉션 로그 비동기 처리를 위한 메시지 큐 |
| **Infrastructure** | **AWS SAM** | Infrastructure as Code (IaC) 기반 리소스 관리 |
| **Frontend** | **Next.js (App Router)** | TypeScript 기반 웹 대시보드 |
| **AI Analysis** | **Google Gemini API** | AI 기반 인기 URL 패턴 및 트렌드 분석 |
| **Security** | **Safe Browsing API** | Google Safe Browsing 연동 악성 URL 필터링 |
| **CI/CD** | **GitHub Actions** | 백엔드/프론트엔드 자동 빌드 및 배포 파이프라인 |

---

### 🏗 상세 아키텍처 (Detailed Architecture)

![Architecture Diagram](./architecture%20diagram.png)

---

### 📂 프로젝트 구조 (Project Structure)

```text
LessURL-Service/
├── LessUrlFunction/             # [Backend] Java 21 서버리스 코드
├── frontend/                    # [Frontend] Next.js 기반 대시보드
├── .github/workflows/           # [CI/CD] GitHub Actions 배포 워크플로우 (deploy.yml)
├── template.yaml                # [IaC] AWS SAM 리소스 정의
├── local-env.json               # 로컬 테스트용 환경 변수 설정 파일
└── api-requests.http            # IntelliJ API 테스트 파일
```

---

### ⚙️ 설정 및 실행 (Configuration & Setup)

#### **1. 필수 GitHub Secrets (Environment Variables)**
프로젝트 배포를 위해 다음 GitHub Secrets 설정이 필요합니다.

| 구분 | 변수명 | 설명 |
| :--- | :--- | :--- |
| **AWS 인증** | `AWS_ACCESS_KEY_ID` | AWS IAM 사용자 액세스 키 |
| | `AWS_SECRET_ACCESS_KEY` | AWS IAM 사용자 비밀 키 |
| **백엔드(SAM)** | `GEMINI_API_KEY` | Google AI Studio API 키 |
| | `SAFE_BROWSING_API_KEY` | Google Safe Browsing API 키 |
| | `PROD_BASE_URL` | 운영용 단축 URL 도메인 (예: `https://lessurl.site`) |
| | `CORS_PROD_ORIGIN` | 허용할 프론트엔드 도메인 (CORS 설정용) |
| | `SAM_S3_BUCKET` | SAM 빌드 산출물 저장용 S3 버킷명 |
| **프론트엔드** | `NEXT_PUBLIC_API_BASE_URL` | 프론트엔드가 호출할 API Gateway 주소 |
| | `NEXT_PUBLIC_ADMIN_TOKEN` | 관리자 페이지 인증용 비밀 토큰 |
| | `FRONTEND_S3_BUCKET` | 프론트엔드 정적 호스팅 S3 버킷명 |
| | `CLOUDFRONT_DISTRIBUTION_ID` | 배포 후 캐시 무효화를 위한 CloudFront ID |

#### **2. 빌드 및 로컬 실행**
```bash
# 백엔드 빌드 및 로컬 실행
cd LessUrlFunction && ./gradlew build
sam local start-api --env-vars local-env.json

# 프론트엔드 실행
cd frontend && npm install && npm run dev
```

---

### 🧪 테스트 및 품질 관리 (Testing)

-   **Unit Test:** JUnit 5를 사용하여 핸들러 로직 검증 (`LessUrlFunction/src/test`)
-   **API Test:** IntelliJ IDEA의 `api-requests.http` 파일을 사용하여 엔드포인트 응답 확인
-   **GitHub Actions:** 코드 푸시 시 자동으로 단위 테스트를 실행하고 아티팩트를 업로드합니다.

---

### 🚢 배포 (Deployment)
-   **Branch:** `deploy` 브랜치에 푸시 시 GitHub Actions가 동작합니다.
-   **Backend:** AWS SAM을 통해 CloudFormation 스택으로 배포됩니다.
-   **Frontend:** 빌드 후 S3 정적 호스팅에 배포되며 CloudFront 캐시가 자동 무효화됩니다.
