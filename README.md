# api-gateway

Spring Cloud Gateway for the JerokySys microservices. It is the **first layer of
security**: every request is authenticated (JWT) and authorized (role per route)
before it is routed to a microservice.

- Default port: `8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html` (Auth / Academic / Invoices)

## Security

| Level | Where | Behaviour |
|---|---|---|
| Authentication | `config/JwtConfig.java` (`NimbusReactiveJwtDecoder`) | Verifies signature (HS256) and expiry with the shared `jwt.secret`. Missing / expired / forged token → **401**, request is never routed |
| Authorization | `config/SecurityConfig.java` (`authorizeExchange`) | Role of the token vs. route + HTTP method → **403** when it does not match |
| Response | `security/SecurityErrors.java` | `401`/`403` as a JSON `Response` envelope, no stack traces |

`jwt.secret`, `jwt.claim.user` and `jwt.claim.role` live in
`src/main/resources/config/private.properties` (ignored by git, see
`private.example.properties`) and must match `jerokysys-auth`. The gateway also
strips client-supplied `X-User` / `X-Role` headers before routing.

### Route rules

| Route | Method | ADMIN | PROFESSOR | SECRETARY |
|---|---|---|---|---|
| `POST /api/v1/login`, `POST /api/v1/users` | POST | public (registration + login) | | |
| `/docs/**`, `/swagger-ui/**`, `/webjars/**` | any | public | | |
| `/api/v1/users/**` | any but POST | yes | 403 | 403 |
| `/api/v1/classes/**`, `/disciplines/**`, `/enrollments/**`, `/exams/**`, `/exam-results/**` | GET | yes | yes | yes |
| same academic routes | POST/PUT/DELETE | yes | yes | **403** |
| `/api/v1/invoices/**`, `/invoice-details/**` | GET | yes | **403** | yes |
| same invoice routes | POST/PUT/DELETE | yes | **403** | yes |
| anything else | any | authenticated required | | |

### Examples

```bash
# sin token -> 401
curl -i localhost:8080/api/v1/classes
#{"data":null,"message":"Authentication required","code":"UNAUTHORIZED","status":401,"success":false}

# login -> token
TOKEN=$(curl -s -X POST localhost:8080/api/v1/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}')

# SECRETARY leyendo academic -> 200
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/classes

# rol equivocado -> 403
curl -i -X POST localhost:8080/api/v1/classes \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Prueba"}'
```

## Run

```bash
./mvnw spring-boot:run
```

Requires Eureka on `http://localhost:8761` and the target services registered.
Add the same `JWT_SECRET` as `jerokysys-auth` in
`src/main/resources/config/private.properties`.

## Tests

```bash
./mvnw test
```
