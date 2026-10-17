Synthetic local order-project fixture, not a production database or authentication implementation.
Java 21, Spring Boot 3, PostgreSQL/Redis are existing infrastructure; this patch uses the existing repository/password/JWT ports.
The LoginController signature and its comment define the existing contract. Implement its pending method and provide JUnit 5 tests.
Keep the legacy entry and other files unchanged. No migrations, dependency changes or actual network access.
Use dependency fakes in tests; all test users and values are synthetic. Database integration is outside this fixture's coverage.
