# ADR-0016 · Resúmenes con IA

**Estado**: aceptada (H17, 2026-11-17, a petición del usuario).

**Contexto**: el usuario quiere un botón «Resumen y claves» que, a partir de los subtítulos `.srt` de una clase, genere un resumen en Markdown de la clase y también de cada sección. Pidió además:

1. Varios proveedores a elegir, incluidos los gratuitos o baratos, y que añadir otro sea fácil.
2. Que el usuario los ordene, y que si uno falla (por ejemplo, sin cuota gratuita) se pase al siguiente.
3. En las clases sin `.srt`, el botón desactivado de momento.

Antes había decidido que los resúmenes se guardan con los datos de la app (sincronizados y exportables a `.md`), nunca en sus carpetas.

**Decisión**:

- **Un único protocolo**, el de *chat completions* de OpenAI (`POST {base}/chat/completions`, `GET {base}/models`, clave como *bearer*), porque todos lo hablan: Gemini (su endpoint compatible), Groq, NVIDIA NIM, OpenRouter, Mistral, Cerebras, DeepSeek y Ollama. Un proveedor nuevo es un *preset* (nombre, URL base, modelo inicial, si necesita clave, si tiene capa gratuita, dónde conseguir la clave). También se acepta cualquier URL compatible.
- **Lista ordenada por dispositivo** (ajuste `ai.summaryProviders`, de dispositivo: las claves no viajan, y cada dispositivo puede tener las suyas). Se prueban en orden los activados. Los motivos para pasar al siguiente son:
  - sin clave guardada;
  - 429 (sin cuota);
  - 401/403 (clave);
  - 402 (saldo);
  - 404 (modelo);
  - texto demasiado largo;
  - 5xx;
  - sin conexión;
  - respuesta vacía.

  El primero que responde se pregunta primero durante el resto del trabajo (las partes de una clase larga, las clases de una sección).
- **Claves**: una por proveedor en el almacén protegido (Android Keystore; llavero por libsecret en Linux), nunca en ajustes, exportaciones ni sincronización.
- **Qué se envía**: solo el texto de los subtítulos (sin números, tiempos ni etiquetas, sin líneas repetidas), el título de la clase y el del curso. Se elige el `.srt` del idioma de la app si lo hay, si no el primero. El resumen se pide en el idioma de la app con secciones fijas: Resumen, Claves, Términos y Para repasar.
- **Clases largas**: la transcripción se trocea en partes de unos 24 000 caracteres (≈ 6 000 *tokens*, cabe en los límites gratuitos más pequeños), se toman notas de cada parte y se combinan.
- **Secciones**: se combinan los resúmenes de sus clases con subtítulos; los que faltan se escriben antes y se guardan. Si son demasiados para una petición, se combinan por grupos.
- **Almacenamiento**: tabla `summary` (esquema v9), clave = id de la lección o de la sección (ADR-0004: el mismo en todos los dispositivos). Se sincroniza como entidad `summary` (servidor: tabla `summaries`, Flyway V8, `/summaries`) con *last writer wins*. También entra en la copia completa (ADR-0015). Se puede guardar como `.md` donde el usuario elija.
- **Dónde está el botón**:
  - en el detalle del curso, en el menú de cada clase, desactivado y con el motivo si no tiene subtítulos;
  - en la cabecera de cada sección que tenga alguna clase con subtítulos;
  - en el reproductor, como panel lateral, si la clase tiene subtítulos.

**Alternativas descartadas**:
- Adaptadores nativos por proveedor (Gemini `generateContent`…): más código y más pruebas por proveedor sin ganar nada para resumir texto.
- Escribir el `.md` junto al vídeo: el usuario prefiere no tocar sus carpetas (pueden ser de solo lectura o compartidas).
- Transcribir el audio cuando no hay `.srt`: fuera de alcance por ahora (el usuario pidió dejar el botón desactivado).
