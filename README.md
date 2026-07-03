# Web DB Admin

A private-network web database management prototype inspired by DataGrip.

## Stack

- Backend: Spring Boot 3, Java 17, JDBC
- Frontend: React, Vite, TypeScript
- State store: H2 for app metadata
- Target database access: server-side JDBC

## Run

Backend:

```bash
cd backend
mvn spring-boot:run
```

Frontend:

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`.

## Implemented MVP

- Connection CRUD and connection testing
- Encrypted password storage
- Metadata browsing for schemas, tables, views, columns, indexes and object details
- SQL formatting, execution and execution-plan endpoint
- Result pagination cap and execution audit logs
- Table edit SQL preview with primary/unique-key guardrails
- CSV/JSON/SQL/XML export from SQL query
- Backup task CRUD, manual execution stub and scheduling fields
- React UI with connection explorer, SQL workspace, result grid and backup task panel

## Notes

Only JDBC drivers present on the backend classpath can be used. H2, MySQL, PostgreSQL, SQL Server, SQLite, MariaDB, ClickHouse and Oracle drivers are included.

Oracle examples:

```text
Service Name: jdbc:oracle:thin:@//localhost:1521/ORCLPDB1
SID:          jdbc:oracle:thin:@localhost:1521:ORCL
```

Oracle table browsing uses an Oracle 11g-compatible `ROWNUM` pagination query. Oracle execution plans use `EXPLAIN PLAN FOR ...` and `DBMS_XPLAN.DISPLAY()`.
