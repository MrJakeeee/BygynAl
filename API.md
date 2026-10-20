# API первого этапа

Базовый путь `/api`. JSON, cookie `JSESSIONID`, ошибки `{"message":"..."}`. Перед изменяющими запросами получите `GET /auth/csrf` и передавайте возвращённый `token` в заголовке `headerName`. После завершения сессии получите новый CSRF-токен.

| Метод | Путь | Тело / результат |
|---|---|---|
| GET | `/auth/csrf` | `{token, headerName}` |
| POST | `/auth/register` | `{name,email,phone,password}` → `{challengeId,expiresAt,message}` |
| POST | `/auth/register/verify` | `{challengeId,code}` → регистрация |
| POST | `/auth/login` | `{email,password}` → профиль или `{twoFactorRequired:true,challenge:{challengeId,expiresAt,message}}` |
| POST | `/auth/login/verify` | `{challengeId,code}` → профиль и сессия |
| POST | `/auth/password/forgot` | `{email}` → `{challengeId,message}`, одинаковое сообщение для неизвестного адреса |
| POST | `/auth/password/reset` | `{challengeId,code,password}` → новый пароль, завершение сессий |
| POST | `/logout` | Завершение текущей сессии, 204 |
| GET | `/profile` | Безопасные данные текущего пользователя, без хешей |
| PATCH | `/profile` | `{name,phone}` |
| POST | `/profile/password` | `{currentPassword,password}` |
| POST | `/profile/email` | `{email,password}` → challenge на новый адрес |
| POST | `/profile/email/verify` | `{challengeId,code}` |
| POST | `/profile/two-factor` | `{password,enabled:true/false}` → challenge |
| POST | `/profile/two-factor/verify?enabled=true` | `{challengeId,code}`, значение enabled должно совпадать с запросом |
| GET | `/buyer/access` | Проверка роли USER |
| GET | `/seller/access` | Проверка роли SELLER после смены временного пароля |
| GET | `/admin/users?search=...` | Поиск пользователей, до 100 записей |
| PATCH | `/admin/users/{id}/block` | `{blocked:true/false}`, самоблокировка запрещена |
| GET | `/admin/establishments` | Список заведений, до 100 записей |
| POST | `/admin/establishments` | `{name,address}` → `{id,message}` |
| POST | `/admin/sellers` | `{name,email,phone,establishmentId}` → `{id,message}`, пароль отправляется письмом |

Повторная отправка: повторите исходный запрос регистрации, входа, восстановления или изменения профиля через 60 секунд. Используйте новый `challengeId`; старый код перестаёт действовать. Роли в ответах: `USER` — покупатель, `SELLER` — сотрудник, `ADMIN` — администратор. Публичной регистрации привилегированных ролей нет.

Коды ответов: 400 — неверные данные/код, 401 — нет сессии/неверный пароль, 403 — роль/блокировка/CSRF/обязательная смена пароля, 409 — конфликт e-mail, 429 — превышен лимит, 503 — SMTP недоступен. Во время обязательной смены пароля доступны только профиль, смена пароля, CSRF и выход.
