-- =============================================================================
-- Chivas Rumberas — Restauración del estado base de la Réplica PostgreSQL
-- Limpia las tablas y restaura el catálogo semilla determinístico.
-- =============================================================================

BEGIN;

TRUNCATE TABLE "bookings", "customers", "chivas", "travels", "users" RESTART IDENTITY CASCADE;

-- 1. Usuarios (Admin y Operador)
INSERT INTO "users" ("id", "email", "passwordHash", "role", "createdAt")
VALUES
  (
    'usr-admin-0001-0000-000000000001',
    'admin@chivasrumberas.com',
    '$2a$10$JQZI99B4sktG9qwj9rT/BezQnx0GAPy/LaEnYhFZmEFoLANh40RAC',
    'admin',
    '2026-11-01 00:00:00.000'
  ),
  (
    'usr-oper-0002-0000-000000000002',
    'operador@chivasrumberas.com',
    '$2a$10$oIcp.8inxi3M80ilt23qrOT6nG7yKx4rk6Aq37zcjrgGfua2Njy9C',
    'operator',
    '2026-11-01 00:00:00.000'
  );

-- 2. Recorridos (Travels)
INSERT INTO "travels" ("id", "name", "description", "durationHours", "highlights", "isActive")
VALUES
  (
    'trv_alumbrados',
    'Ruta Navideña y Alumbrados',
    'Recorrido por los principales puntos iluminados y parques de Medellín.',
    4,
    ARRAY['Río Medellín', 'Parque Norte', 'Avenida La Playa', 'Parada para buñuelos y natilla'],
    true
  ),
  (
    'trv_miradores',
    'Tour Miradores Nocturnos',
    'Ruta panorámica de fiesta por los mejores miradores del Valle de Aburrá.',
    3,
    ARRAY['Mirador Las Palmas', 'Pueblito Paisa', 'Parada de fotos panorámica'],
    true
  ),
  (
    'trv_sabaneta',
    'Rumba Fonda y Sabaneta',
    'Recorrido rumbero por La 70, Poblado y el Parque de Sabaneta.',
    4,
    ARRAY['Boulevard La 70', 'Parque Lleras', 'Parque de Sabaneta'],
    true
  );

-- 3. Chivas
INSERT INTO "chivas" ("id", "name", "description", "capacity", "imageUrls", "pricePerHour", "status", "features", "createdAt", "updatedAt")
VALUES
  (
    'la-coqueta-1954',
    'La Coqueta',
    'Chiva tradicional paisa equipada con sonido profesional y luces LED.',
    50,
    ARRAY['https://images.unsplash.com/photo-1544620347-c4fd4a3d5957?auto=format&fit=crop&w=1000&q=80'],
    280000,
    'available',
    ARRAY['Sonido profesional 4000W', 'Luces robóticas', 'Animador / DJ', 'Parrilla para equipaje'],
    '2026-11-01 00:00:01.000',
    '2026-11-01 00:00:01.000'
  ),
  (
    'la-perla-1959',
    'La Perla',
    'Chiva clásica restaurada ideal para grupos empresariales y celebraciones familiares.',
    45,
    ARRAY['https://images.unsplash.com/photo-1570125909232-eb263c188f7e?auto=format&fit=crop&w=1000&q=80'],
    300000,
    'available',
    ARRAY['Sonido envolvente', 'Barra auxiliar', 'Luces láser', 'Micrófono inalámbrico'],
    '2026-11-01 00:00:02.000',
    '2026-11-01 00:00:02.000'
  ),
  (
    'la-parrandera-1962',
    'La Parrandera',
    'Chiva rumbera con pista central ampliada y máquina de humo.',
    40,
    ARRAY['https://images.unsplash.com/photo-1517524008697-84bbe3c3fd98?auto=format&fit=crop&w=1000&q=80'],
    260000,
    'available',
    ARRAY['Máquina de humo', 'Sonido 3500W', 'Conectividad Bluetooth', 'Nevera para bebidas'],
    '2026-11-01 00:00:03.000',
    '2026-11-01 00:00:03.000'
  ),
  (
    'el-arriero-1957',
    'El Arriero',
    'Chiva típica antioqueña de gran capacidad para tours turísticos y despedidas.',
    55,
    ARRAY['https://images.unsplash.com/photo-1464219789935-c2d9d9aba644?auto=format&fit=crop&w=1000&q=80'],
    320000,
    'available',
    ARRAY['Capacidad extendida 55 pax', 'Sonido 5000W', 'DJ en vivo', 'Iluminación LED RGB'],
    '2026-11-01 00:00:04.000',
    '2026-11-01 00:00:04.000'
  );

-- 4. Clientes iniciales
INSERT INTO "customers" ("id", "name", "email", "phone", "createdAt")
VALUES
  ('cust-0001-0000-0000-000000000001', 'Carolina Montoya', 'carolina.montoya@ejemplo.com', '3001234567', '2026-11-01 10:00:00.000'),
  ('cust-0002-0000-0000-000000000002', 'Andrés Restrepo', 'andres.restrepo@ejemplo.com', '3109876543', '2026-11-01 10:05:00.000'),
  ('cust-0003-0000-0000-000000000003', 'Valentina Gómez', 'valentina.gomez@ejemplo.com', '3204567890', '2026-11-01 10:10:00.000');

-- 5. Reservas semilla iniciales
INSERT INTO "bookings" (
  "id", "chivaId", "travelId", "customerId", "customerName", "customerEmail", "customerPhone",
  "guestCount", "eventDate", "startTime", "durationHours", "totalPrice", "status",
  "notes", "calendarSyncStatus", "createdAt", "updatedAt"
)
VALUES
  (
    'CR-SEED01',
    'la-coqueta-1954',
    'trv_alumbrados',
    'cust-0001-0000-0000-000000000001',
    'Carolina Montoya',
    'carolina.montoya@ejemplo.com',
    '3001234567',
    42,
    '2026-11-20 00:00:00.000',
    '18:00',
    4,
    1120000,
    'confirmed',
    'Cumpleaños empresarial en La Coqueta.',
    'pending',
    '2026-11-01 12:00:00.000',
    '2026-11-01 12:00:00.000'
  ),
  (
    'CR-SEED02',
    'la-perla-1959',
    'trv_miradores',
    'cust-0002-0000-0000-000000000002',
    'Andrés Restrepo',
    'andres.restrepo@ejemplo.com',
    '3109876543',
    35,
    '2026-11-20 00:00:00.000',
    '20:00',
    3,
    900000,
    'confirmed',
    'Despedida de fin de año en La Perla.',
    'pending',
    '2026-11-01 12:05:00.000',
    '2026-11-01 12:05:00.000'
  ),
  (
    'CR-SEED03',
    'la-parrandera-1962',
    'trv_sabaneta',
    'cust-0003-0000-0000-000000000003',
    'Valentina Gómez',
    'valentina.gomez@ejemplo.com',
    '3204567890',
    38,
    '2026-11-25 00:00:00.000',
    '21:00',
    4,
    1040000,
    'confirmed',
    'Rumba nocturna hasta la 01:00 AM.',
    'pending',
    '2026-11-01 12:10:00.000',
    '2026-11-01 12:10:00.000'
  );

COMMIT;
