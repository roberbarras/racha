# Resúmenes con IA («Resumen y claves»)

Decisión y detalles técnicos: [ADR-0016](adr/0016-resumenes-con-ia.md).

Racha puede escribir un **resumen en Markdown** de una clase a partir de sus subtítulos `.srt`, y también el de una sección completa. Tiene siempre las mismas partes:

- **Resumen**;
- **Claves**;
- **Términos**;
- **Para repasar**, con preguntas para comprobar que lo has entendido.

## Cómo se usa

1. **Ajustes → Resúmenes con IA → Añadir proveedor** y elige uno de la lista.
2. Pulsa el engranaje del proveedor y pega su **clave** (enlace «Conseguir clave»). Con **Probar** compruebas que responde y con **Cargar modelos** eliges otro modelo.
3. Puedes añadir varios y **ordenarlos** con las flechas: se prueban en ese orden y, si uno falla, lo intenta el siguiente. Los motivos de fallo son quedarse sin cuota gratuita, una clave incorrecta, un modelo que ya no existe o un servicio caído. El interruptor desactiva uno sin borrarlo.
4. En el detalle de un curso tienes tres sitios para pedir el resumen:
   - **menú ⋯ de una clase → Resumen y claves**;
   - el botón **Resumen de la sección** en la cabecera de cada sección;
   - en el reproductor, el botón ✦ abre el resumen en el panel lateral sin dejar de ver el vídeo.
5. En la pantalla del resumen tienes tres acciones:
   - **Guardar como .md**, para guardarlo donde quieras;
   - **Volver a generar**;
   - **Borrar**.

Las clases **sin subtítulos** muestran la opción desactivada («Necesita subtítulos (.srt)»), y el reproductor no muestra el botón.

## Proveedores incluidos

| Proveedor | Coste | Clave |
|---|---|---|
| Google Gemini | Capa gratuita con límites | https://aistudio.google.com/apikey |
| Groq | Capa gratuita con límites | https://console.groq.com/keys |
| NVIDIA NIM | Créditos gratuitos | https://build.nvidia.com/settings/api-keys |
| OpenRouter | Modelos `:free` gratuitos con límites | https://openrouter.ai/settings/keys |
| Mistral | Capa gratuita con límites | https://console.mistral.ai/api-keys |
| Cerebras | Capa gratuita con límites | https://cloud.cerebras.ai |
| DeepSeek | De pago, barato | https://platform.deepseek.com/api_keys |
| Ollama | Gratis, en tu propio equipo, sin clave | — |
| Compatible con OpenAI | El que pongas (URL base, modelo y clave) | — |

Los límites gratuitos los pone cada proveedor y cambian: por eso conviene tener dos o tres en la lista.

**Ollama**: en Linux, `http://localhost:11434/v1` funciona tal cual. En Android, «localhost» es el propio móvil. Para usar el Ollama de tu PC pon su dirección, y ten en cuenta que Android solo permite `https://`, así que tendrá que estar detrás de un proxy con certificado.

## Privacidad

- Solo se envía el **texto de los subtítulos** (sin tiempos), el título de la clase y el del curso. Nunca el vídeo ni otros ficheros.
- Las **claves** se guardan en el almacén protegido del sistema (llavero en Linux, Android Keystore). Nunca van a los ajustes, la exportación, la copia de seguridad ni la sincronización. La lista de proveedores es de cada dispositivo.
- Los **resúmenes** se guardan con los datos de la app, **nunca en tus carpetas**. Se sincronizan con tu cuenta (escritos en el móvil, también están en el PC) y entran en la copia de seguridad completa.

## Clases largas y secciones

- Una transcripción larga se divide en partes. Se toman notas de cada una y se combinan en un único resumen, así cabe en los límites de los modelos gratuitos.
- El resumen de una sección se hace con los resúmenes de sus clases: los que falten se escriben antes y quedan guardados.

## Tests

- Dominio (`SummariesTest`):
  - de `.srt` a texto;
  - troceado;
  - instrucciones en español e inglés;
  - lista de proveedores.
- JVM con SQLite real (`SummarizeWithAiTest`):
  - pasa al siguiente proveedor sin cuota y se queda con el que responde;
  - se salta los desactivados y los que no tienen clave;
  - clases largas por partes;
  - sección a partir de sus clases;
  - sin subtítulos, sin proveedores, todos fallan;
  - sincronización.
- Cliente HTTP (`KtorChatClientTest`), contra un servidor simulado, nunca uno real:
  - petición;
  - clave;
  - lista de modelos;
  - errores con su código;
  - quitar el «pensamiento» de los modelos que razonan.
- UI de escritorio (`SummariesUiTest`):
  - Markdown;
  - opción desactivada sin `.srt`;
  - generar y guardar `.md`;
  - añadir un proveedor desde la lista.
- Servidor (`SyncApiTest`): los resúmenes viajan entre dispositivos, `/summaries` y la validación.
