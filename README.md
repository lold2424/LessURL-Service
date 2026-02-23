# 🔗 LessURL - 지능형 서버리스 URL 단축 서비스

**LessURL**은 AWS 서버리스 아키텍처와 최신 AI 기술을 결합하여 탄생한 스마트 URL 관리 플랫폼입니다. 단순한 링크 단축을 넘어, AI 기반의 실시간 유해성 분석과 심도 있는 클릭 통계 리포트를 제공합니다.

## 🚀 주요 기능 (Key Features)

### 1. 지능형 URL 단축 및 보안
*   **AI 기반 위협 탐지**: Google Gemini 1.5 Flash와 Google Safe Browsing API를 이중으로 활용하여 피싱, 악성코드 사이트를 실시간으로 차단합니다.
*   **유연한 가시성**: `PUBLIC`(공개 대시보드 노출) 또는 `PRIVATE`(나만 확인) 설정을 지원합니다.

### 2. 고도화된 통계 분석 및 AI 인사이트
*   **정밀 트래킹**: 시간별, 일별 클릭 추이와 유입 경로(Referer)를 데이터 시각화하여 제공합니다.
*   **AI 리포트**: Gemini 2.5 Flash가 수집된 통계를 분석하여 사용자 행동 패턴에 대한 한 줄 인사이트와 전문가 팁을 생성합니다.
*   **지능형 캐싱**: AI API 할당량 보호 및 응답 속도 향상을 위해 분석 결과를 **24시간 동안 DB에 캐싱**하는 로직이 적용되어 있습니다.

### 3. 현대적인 사용자 경험 (UX)
*   **브랜드 아이덴티티**: Deep Trust Navy(#002855)와 Energizing Orange(#FF6B35)를 활용한 전문적이고 역동적인 UI.
*   **다국어 지원**: 한국어 및 영어 자동 전환 및 설정 유지 기능을 제공합니다.
*   **실시간 대시보드**: 최신 공개 링크들을 한눈에 확인하고 즉시 이동 및 통계 조회가 가능합니다.

---

## 🛠 기술 스택 (Tech Stack)

### Backend (Serverless)
- **Runtime**: Java 21 (AWS Lambda)
- **Optimization**: AWS Lambda **SnapStart** 적용 (Cold Start 최적화)
- **Framework**: AWS Serverless Application Model (SAM)
- **Database**: Amazon DynamoDB (On-Demand)
- **AI/Security**: Google Gemini API (1.5 & 2.5 Flash), Google Safe Browsing API

### Frontend
- **Framework**: **Next.js 16** (App Router, Static Export)
- **Library**: React 19, TypeScript
- **Styling**: **Tailwind CSS 4** (최신 버전 적용)
- **Infrastructure**: AWS S3, Amazon CloudFront

### DevOps & Monitoring
- **CI/CD**: GitHub Actions (자동 빌드 및 배포)
- **Monitoring**: Amazon CloudWatch Alarms (에러율, 실행 시간, Throttling 감지 및 SNS 이메일 알림)

---

## 🏗 아키텍처 (Architecture)

![Architecture Diagram](./architecture%20diagram.png)

---

## 💻 로컬 개발 환경 설정 (Local Development)

### Prerequisites
- AWS SAM CLI, Java 21, Docker
- Google Gemini API Key

### Setup
1.  **데이터베이스 실행**: `docker-compose up -d`
2.  **테이블 초기화**: `setup-local-db.bat` 실행
3.  **백엔드 실행**:
    ```bash
    sam build
    sam local start-api --env-vars local-env.json --port 3001
    ```
4.  **프론트엔드 실행**:
    ```bash
    cd frontend
    npm install
    npm run dev
    ```

---

## 📡 모니터링 알람 (Monitoring)
본 프로젝트는 안정적인 운영을 위해 다음과 같은 CloudWatch 알람이 설정되어 있습니다.
- **Lambda Errors**: 분당 10회 이상 발생 시 알림
- **Lambda Duration**: 평균 실행 시간 3초 초과 시 알림
- **API Gateway 5XX/4XX**: 이상 징후 발생 시 실시간 감지
- **DynamoDB Throttling**: 읽기/쓰기 용량 초과 시 즉시 대응 가능

---

&copy; 2026 LessURL Service. Powered by AWS Serverless.
