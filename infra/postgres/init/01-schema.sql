-- =============================================================================
-- Chivas Rumberas — Esquema PostgreSQL (Réplica exacta de Prisma Schema)
-- Generado a partir de backend-express/prisma/schema.prisma y sus migraciones.
-- La base de datos original (Neon) NO se toca.
-- =============================================================================

-- 1. Enumerados
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'ChivaStatus') THEN
        CREATE TYPE "ChivaStatus" AS ENUM ('available', 'maintenance', 'retired');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'BookingStatus') THEN
        CREATE TYPE "BookingStatus" AS ENUM ('pending', 'confirmed', 'cancelled', 'completed');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'CalendarSyncStatus') THEN
        CREATE TYPE "CalendarSyncStatus" AS ENUM ('pending', 'synced', 'failed');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'UserRole') THEN
        CREATE TYPE "UserRole" AS ENUM ('admin', 'operator');
    END IF;
END $$;

-- 2. Tabla: chivas
CREATE TABLE IF NOT EXISTS "chivas" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "description" TEXT NOT NULL,
    "capacity" INTEGER NOT NULL,
    "imageUrls" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    "pricePerHour" DOUBLE PRECISION NOT NULL,
    "status" "ChivaStatus" NOT NULL DEFAULT 'available',
    "features" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "chivas_pkey" PRIMARY KEY ("id")
);

-- 3. Tabla: travels
CREATE TABLE IF NOT EXISTS "travels" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "description" TEXT NOT NULL,
    "durationHours" DOUBLE PRECISION NOT NULL,
    "highlights" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    "isActive" BOOLEAN NOT NULL DEFAULT true,

    CONSTRAINT "travels_pkey" PRIMARY KEY ("id")
);

-- 4. Tabla: customers
CREATE TABLE IF NOT EXISTS "customers" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "phone" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "customers_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "customers_email_key" ON "customers"("email");

-- 5. Tabla: bookings
CREATE TABLE IF NOT EXISTS "bookings" (
    "id" TEXT NOT NULL,
    "chivaId" TEXT NOT NULL,
    "travelId" TEXT NOT NULL,
    "customerId" TEXT,
    "customerName" TEXT NOT NULL,
    "customerEmail" TEXT NOT NULL,
    "customerPhone" TEXT NOT NULL,
    "guestCount" INTEGER NOT NULL,
    "eventDate" TIMESTAMP(3) NOT NULL,
    "startTime" TEXT NOT NULL,
    "durationHours" DOUBLE PRECISION NOT NULL,
    "totalPrice" DOUBLE PRECISION NOT NULL,
    "status" "BookingStatus" NOT NULL DEFAULT 'pending',
    "notes" TEXT,
    "googleEventId" TEXT,
    "calendarSyncStatus" "CalendarSyncStatus" NOT NULL DEFAULT 'pending',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "bookings_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "bookings_chivaId_fkey" FOREIGN KEY ("chivaId") REFERENCES "chivas"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "bookings_travelId_fkey" FOREIGN KEY ("travelId") REFERENCES "travels"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "bookings_customerId_fkey" FOREIGN KEY ("customerId") REFERENCES "customers"("id") ON DELETE SET NULL ON UPDATE CASCADE
);

-- Índices para consultas frecuentes de disponibilidad, calendario mensual y paginación
CREATE INDEX IF NOT EXISTS "bookings_chivaId_eventDate_idx" ON "bookings"("chivaId", "eventDate");
CREATE INDEX IF NOT EXISTS "bookings_status_eventDate_idx" ON "bookings"("status", "eventDate");
CREATE INDEX IF NOT EXISTS "bookings_createdAt_id_idx" ON "bookings"("createdAt" DESC, "id" DESC);

-- 6. Tabla: users
CREATE TABLE IF NOT EXISTS "users" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "passwordHash" TEXT NOT NULL,
    "role" "UserRole" NOT NULL DEFAULT 'operator',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "users_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "users_email_key" ON "users"("email");
