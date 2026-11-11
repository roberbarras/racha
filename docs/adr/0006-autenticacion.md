# ADR-0006 · Autenticación

**Estado**: aceptada. **Desviación** de §7.2 y §12.9 (OAuth Google/GitHub aplazado por decisión del usuario).

**Decisión**: email + contraseña (BCrypt), JWT de acceso corto + refresh rotativo guardado con hash en `sessions`, multiusuario real. El diseño deja hueco para:
- **OTP por email / MFA**: Spring Security 7 incluye login con token de un solo uso y soporte MFA; bastará configurar SMTP.
- **OAuth** (Google/GitHub u otros): flujo para apps nativas RFC 8252 + PKCE (navegador del sistema → callback del servidor → código de un solo uso → app), tabla `oauth_accounts`.
