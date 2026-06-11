# Database Schema

이 문서는 온마루(OnMaru) 프로젝트의 데이터베이스 스키마 구조를 정의합니다.
아래의 DBML 코드를 복사하여 [dbdiagram.io](https://dbdiagram.io/)에 붙여넣으면 시각화된 ERD(관계도)를 볼 수 있습니다.

```dbml
// 예시 테이블 정의
Table users {
  id integer [primary key]
  username varchar
  created_at timestamp
}

Table tours {
  id integer [primary key]
  title varchar
  location varchar
}

// 예시 관계 정의
// Ref: tours.user_id > users.id
```
