Eres un ingeniero frontend senior con muy buen ojo de diseño. Trabajas en "chivas-rumberas-frontend" (Next.js 16 App Router, React 19, TypeScript, Tailwind 4, Animate Icons, clsx). El proyecto es de un cliente real y debe quedar listo para entregar. El backend ya está actualizado y su contrato está más abajo. Mantén el alcance ajustado a lo listado: no agregues funciones extra.

ANTES DE ESCRIBIR CÓDIGO
Lee AGENTS.md y respétalo: esta versión de Next.js puede tener cambios incompatibles, consulta la documentación incluida en el proyecto antes de usar APIs que no conozcas. Lee README.md, globals.css, tailwind.config.mjs, src/components/ui, src/components/layout, src/lib/api, src/lib/types, src/proxy.ts y src/app/api/admin. Identifica colores, tipografías, radios, sombras, espaciados y el tema claro/oscuro ya definidos: son la única fuente de verdad visual.

SKILLS INSTALADAS Y CÓMO USARLAS
Hay skills instaladas en el proyecto. Léelas antes de empezar. Son guía de calidad, no una licencia para rediseñar.
- vercel-react-best-practices: aplícala mientras escribes componentes, rutas y consumo de datos. Prioriza Server Components por defecto y deja el código cliente solo en componentes hoja que lo necesiten (calendario, uploader, formularios). Evita cascadas de fetch, mantén ligero el bundle y no hagas re-renders innecesarios.
- vercel-react-view-transitions: úsala para las transiciones entre estados (cambio de mes del calendario, apertura del detalle de un día, paso entre pantallas del seguimiento) SOLO si la documentación de Next.js y React incluida en el proyecto confirma que está soportado en estas versiones y lo que requiere de configuración. Si no lo está, usa CSS/Tailwind. Nunca instales una librería de animación.
- emil-design-eng y review-animations: guían las decisiones de animación (curvas, duraciones, propiedades, origen de la transformación) y revisan lo que escribiste. Las acciones de teclado y de alta frecuencia no se animan. Anima solo transform y opacity.
- web-design-guidelines: es la revisión final de cada tarea de interfaz (accesibilidad, formularios, imágenes, rendimiento, experiencia de uso).
PRECEDENCIA: si una skill contradice este prompt o AGENTS.md (paleta, fuentes, iconos, radios, dependencias, alcance), gana este prompt. Ignora cualquier sugerencia de skill que cambie la estética existente o agregue librerías. No generes especificaciones ni planes nuevos: el alcance ya está definido abajo.

REGLA DE ORO DE DISEÑO
No cambies la estética. No toques el hero, la navegación, el footer, la home ni los colores existentes, salvo lo estrictamente necesario para integrar lo nuevo. Todo lo nuevo debe verse hecho por la misma persona que hizo lo existente:
- Usa SOLO los tokens, colores y tipografías ya definidos y reutiliza src/components/ui. Si falta un componente, créalo con el mismo lenguaje visual.
- Prohibido: emojis, guiones largos (—) en cualquier texto visible, gradientes morados o azules genéricos, sombras exageradas, íconos de librerías nuevas, textos de relleno tipo "¡Descubre el poder de...", glassmorphism gratuito, puntos de estado decorativos, etiquetas pequeñas sobre cada título. Los íconos salen de Animate Icons o de los propios del proyecto.
- Textos en español, sobrios y concretos, con el tono de marca ya usado. Relee todo el texto nuevo antes de entregar y reescribe lo que suene genérico o forzado.
- Animaciones sutiles y con propósito: cada una debe poder justificarse en una frase (jerarquía, feedback o cambio de estado). Ejemplos: entrada escalonada de filas o tarjetas, barra de progreso en subidas, línea de estados del seguimiento que se va llenando, deslizamiento suave al cambiar de mes en el calendario, apertura del detalle de un día, hover/focus/pressed, skeletons. Respeta prefers-reduced-motion en todas.
- Un solo sistema de radios de borde y un solo color de acento: los que ya existen. Contraste suficiente en botones, inputs y placeholders, en tema claro y oscuro. Ningún botón parte su texto en dos líneas en escritorio.
- Accesible y responsive: foco visible, labels, aria, móvil primero.

CONTRATO DE API (definitivo, entregado por el backend)
Todas las rutas admin requieren Authorization: Bearer <JWT>. En el frontend esas llamadas pasan por src/app/api/admin (proxy con la cookie HttpOnly admin_token). Los tipos viven en src/lib/types. Errores siempre como { error: { type, message } }.

CAMBIO QUE ROMPE COMPATIBILIDAD
GET /bookings (admin) antes devolvía un arreglo plano y ahora devuelve un objeto paginado, y exige rol admin (403 si el rol es operator). Antes de tocar nada, busca con grep TODOS los usos actuales de la función que lista reservas (listado de /gestion-interna/reservas, resumen o contadores del dashboard en /gestion-interna, etc.) y actualízalos todos. Para contadores del dashboard no cuentes el arreglo de la primera página: usa meta.total, pidiendo limit=1 con el filtro de status que corresponda. GET /chivas NO cambió: sigue siendo un arreglo plano.

1. GET /bookings (admin)
   Query opcional: page (default 1, entero >= 1), limit (default 10, entero 1..50), status (pending | confirmed | cancelled | completed), q (insensible a mayúsculas: código CR-XXXXXX, nombre o correo del cliente).
   200: { data: BookingResponseDto[], meta: { page, limit, total, totalPages } }
   BookingResponseDto: { id, chivaId, chivaName, travelId, travelName, customerName, customerEmail, customerPhone, guestCount, eventDate "YYYY-MM-DD", startTime "HH:mm", endTime "HH:mm", durationHours, totalPrice (entero COP), status, notes, calendarSyncStatus, createdAt (ISO UTC) }
   403 si el usuario no es admin: muestra un estado de "sin permisos" con el tono del sitio, no el error genérico.

2. GET /uploads/signature (admin), sin body ni query
   200: { cloudName, apiKey, timestamp, signature, folder, allowedFormats: ["jpg","png","webp"], maxFileSize: 5242880 }
   Subida directa a Cloudinary desde el navegador: POST https://api.cloudinary.com/v1_1/{cloudName}/image/upload con FormData que incluya EXACTAMENTE: file, api_key (apiKey), timestamp, signature, folder, allowed_formats ("jpg,png,webp") y max_file_size (5242880). No agregues, quites ni cambies ningún otro parámetro: la firma depende de ellos. Toma secure_url de la respuesta y envíalo en imageUrls.
   La firma caduca: pide una nueva antes de cada tanda de subidas y no la guardes en caché. Usa allowedFormats y maxFileSize para validar en el cliente antes de subir. Si Cloudinary rechaza la subida por firma o por un parámetro inválido, NO modifiques los parámetros por tu cuenta: repórtalo en tu entrega final con el mensaje de error exacto.

3. POST /chivas (admin)
   Body: { name, description, capacity, pricePerHour, features: string[], status, imageUrls: string[] } (máximo 10 URLs; imageUrls[0] es la foto principal)
   201: ChivaResponseDto { id, name, description, capacity, imageUrls, pricePerHour, status, features }

4. PATCH /chivas/:id (admin)
   Edición parcial: cualquier combinación de campos. Si envías imageUrls, REEMPLAZA el arreglo completo respetando el nuevo orden y las eliminaciones (máximo 10). Por eso, al cambiar fotos envía siempre el arreglo final completo y ordenado. Envía solo los campos que cambiaron.
   200: ChivaResponseDto actualizado.

5. DELETE /chivas/:id (admin)
   Borrado lógico (status = "retired"). 200: ChivaResponseDto con status "retired" (no es 204). Si tiene reservas futuras pending o confirmed responde 409 con error.type = "ChivaHasActiveBookingsError": muestra el message del backend en el modal.

6. GET /bookings/track (público, con límite estricto: 15 peticiones por 15 minutos)
   Query requerido: code (CR-XXXXXX), email.
   200: { id, code, status, createdAt, chivaId, chivaName, travelId, travelName, eventDate, startTime, endTime, durationHours, guestCount, totalPrice }
   404 idéntico si el código no existe o el correo no coincide: { error: { type: "BookingNotFoundError", message: "No se encontró ninguna reserva con los datos proporcionados." } }
   429 si se excede el límite: muestra un mensaje amable pidiendo esperar unos minutos antes de volver a intentar. El endpoint no devuelve nombre del cliente, notas ni estado de calendario: no los muestres en esta pantalla.

7. POST /bookings/:id/calendar-sync (admin), sin body
   Solo para reservas con status "confirmed". 200: BookingResponseDto con calendarSyncStatus "synced" o "failed". PATCH /bookings/:id/status y GET /bookings/:id también incluyen ahora calendarSyncStatus.

8. GET /bookings/calendar (admin)
   Query requerido: month (YYYY-MM).
   200: { month, chivas: [{ id, name }], days: [{ date, chivasCount, chivas: [{ chivaId, chivaName, slots: [{ bookingId, startTime, endTime, durationHours, customerName, travelName, guestCount, endsNextDay? }] }] }] }
   Solo reservas confirmadas y solo los días con al menos una. "chivas" lista únicamente las chivas que aparecen en ese mes. Un slot con endsNextDay=true pertenece al día de inicio y termina pasada la medianoche; el día siguiente NO aparece marcado por este endpoint, no lo inventes.

DATOS DE PRUEBA DEL BACKEND (seed)
Hay reservas CR-SEED01 (La Coqueta, 20 nov 2026, 18:00-22:00, correo carolina.montoya@ejemplo.com, confirmada, sincronizada), CR-SEED02 (La Perla, 20 nov 2026, 20:00-23:00) y CR-SEED03 (La Parrandera, 25 nov 2026, 21:00-01:00, endsNextDay). El 20 de noviembre tiene dos chivas ocupadas. Úsalas para probar el calendario, el detalle del día y el seguimiento.

FECHAS, HORAS Y DINERO
- Nunca uses new Date("YYYY-MM-DD") para mostrar o comparar fechas: se interpreta como UTC y en Colombia (UTC-5) muestra el día anterior. Descompón la cadena en año, mes y día, o construye la fecha en hora local. "Hoy" y createdAt se muestran en la zona America/Bogota.
- Fechas en español (por ejemplo "20 de noviembre de 2026") y horas en formato 24 h tal como llegan.
- Dinero con Intl.NumberFormat en es-CO, en pesos colombianos, sin decimales.

TAREAS

1) Paginación SOLO en el listado de reservas del admin
- Componente Pagination en src/components/ui (anterior/siguiente, números con elipsis, activo con color de marca, deshabilitado, accesible). Sincroniza page, status y q con la URL (searchParams).
- Úsalo solo en /gestion-interna/reservas, con filtro por estado y buscador con debounce. Al cambiar un filtro vuelve a la página 1. Skeleton al cargar y estados vacío, de error y de sin permisos (403) con el tono del sitio.
- NO pagines el catálogo de la home ni /gestion-interna/chivas. Actualiza src/lib/api, src/lib/types y todos los usos existentes del listado de reservas (ver el cambio que rompe compatibilidad).

2) Gestión de chivas (admin)
- En /gestion-interna/chivas: crear chiva, editar (nombre, descripción, capacidad, precio por hora, características, estado) y retirar (modal de confirmación; muestra el mensaje del 409). Reutiliza el estilo del panel actual. La lista de chivas del panel muestra todos los estados.
- Uploader de fotos: arrastrar y soltar o seleccionar varios archivos, validar tipo y tamaño con lo que devuelve /uploads/signature, vista previa, barra de progreso por archivo, reintento ante error, eliminar y reordenar (la primera es la principal). Máximo 10 fotos. Sube directo a Cloudinary como se indica en el contrato y luego guarda el arreglo completo y ordenado con PATCH /chivas/:id (o junto al POST al crear).
- Configura next.config.ts (images.remotePatterns) para el dominio res.cloudinary.com.

3) Seguimiento de reserva para el cliente
- Nueva ruta pública /seguimiento (grupo (public)): formulario con código y correo; si la URL trae ?code=, precarga el código. Muestra una línea de estados (pendiente, confirmada, completada, o cancelada) calculada a partir del estado actual, con animación de entrada, y el resumen de la reserva con los campos que devuelve el endpoint (chiva, recorrido, fecha, horario, invitados, total, fecha de creación).
- Error genérico y amable cuando no coincide (404) y mensaje de espera cuando se excede el límite (429). Deshabilita el botón mientras se envía para evitar peticiones repetidas.
- En /confirmacion muestra el código de reserva de forma destacada y un enlace claro a "Seguir mi reserva". Añade un acceso discreto a /seguimiento en el menú o footer sin alterar su diseño.

4) Panel de reservas: Google Calendar
- En el detalle de reserva (y como indicador discreto en el listado) muestra el estado de sincronización (sincronizada, pendiente, fallida) solo para reservas confirmadas, con un indicador sobrio de los colores existentes. Si está fallida, botón "Reintentar sincronización" que llama a POST /bookings/:id/calendar-sync, con feedback de carga y resultado, y actualiza el estado mostrado con la respuesta.

5) Dashboard: calendario de chivas confirmadas
Ubicación: /gestion-interna, como una sección más del dashboard, respetando su grilla y estilo.
- Calendario mensual hecho a mano con Tailwind (sin librerías de calendario): encabezado con mes y año, botones anterior/siguiente/hoy, semana desde el lunes, días en español, hoy marcado (calculado en America/Bogota). El mes actual vive en ?month=YYYY-MM; cada cambio de mes hace fetch a /bookings/calendar vía una ruta en src/app/api/admin, con skeleton y una transición suave.
- Color por chiva: el endpoint solo lista las chivas que aparecen en el mes, así que NO asignes colores con esa lista (cambiarían de un mes a otro). Obtén la lista completa con GET /chivas (todos los estados, incluidas las retiradas), ordénala por id y asigna cada color con ese orden estable, tomado de los tokens de la paleta existente, legible en tema claro y oscuro. Si la paleta no alcanza, diferencia además con un marcador con la inicial de la chiva; nunca uses colores ajenos al sitio. No dependas solo del color.
- Celdas: un día con una sola chiva ocupada se resalta con el color de esa chiva. Un día con dos o más chivas ocupadas se resalta de forma claramente distinta (por ejemplo celda dividida en franjas con los colores de cada chiva y el número de chivas). Días sin reservas, neutros.
- Leyenda debajo del calendario con las chivas y su color, más la indicación de lo que significa un día con varias chivas.
- Detalle del día: al hacer clic en un día con reservas se abre un panel lateral en escritorio y un bottom sheet en móvil, con animación de entrada y cierre con Escape. Muestra la fecha completa y, por cada chiva ocupada, su nombre con el color y la lista de rangos de horas ("18:00 - 22:00", con "termina al día siguiente" si endsNextDay), más cliente, recorrido, invitados y enlace a /gestion-interna/reservas/:id. Si hay varias chivas, indícalo explícito ("2 chivas ocupadas este día"). Sin gráficos extra: la lista es suficiente.
- Estados: mes sin reservas ("Sin chivas confirmadas este mes") y error con botón de reintentar. Cada celda lleva aria-label ("20 de noviembre, 2 chivas ocupadas") y los días con reservas son botones enfocables.

FLUJO DE TRABAJO POR TAREA
1. Implementa la tarea aplicando vercel-react-best-practices.
2. Si la tarea tiene animaciones, pásales review-animations y corrige lo que marque.
3. Revisa los archivos nuevos o modificados de interfaz con web-design-guidelines y corrige lo que marque, sin cambiar la estética.
4. Corre lint y build, prueba contra el backend con los datos del seed, y revisa en móvil y escritorio, claro y oscuro.
5. Haz un commit pequeño en español y pasa a la siguiente tarea.

REGLAS
- Cambios mínimos fuera de lo listado. Antes de modificar un componente existente, confirma que no se puede lograr creando uno nuevo.
- Sin dependencias nuevas salvo que sea imprescindible; si agregas una, justifícala. Nada que requiera pago.
- TypeScript estricto, sin any.
- Al final entrégame los archivos creados o modificados, una descripción de cada pantalla nueva (en especial el calendario con un día de una chiva, un día con varias chivas y el detalle), lo que marcaron las revisiones de las skills y cómo lo resolviste, los usos del listado de reservas que tuviste que actualizar por el cambio de formato, y cualquier diferencia que encuentres respecto al contrato de API.