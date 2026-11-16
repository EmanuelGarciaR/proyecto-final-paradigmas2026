import { Request, Response, NextFunction } from "express";
import {
  collectDefaultMetrics,
  Counter,
  Histogram,
  register,
} from "prom-client";

collectDefaultMetrics({ prefix: "chivas_rumberas_" });

const httpRequestsTotal = new Counter({
  name: "chivas_rumberas_http_requests_total",
  help: "Total de solicitudes HTTP recibidas.",
  labelNames: ["method", "route", "status_code"] as const,
});

const httpRequestDuration = new Histogram({
  name: "chivas_rumberas_http_request_duration_seconds",
  help: "Duración de las solicitudes HTTP en segundos.",
  labelNames: ["method", "route"] as const,
  buckets: [0.005, 0.01, 0.05, 0.1, 0.5, 1, 2, 5],
});

export const metricsMiddleware = (
  req: Request,
  res: Response,
  next: NextFunction
): void => {
  const start = process.hrtime.bigint();

  res.on("finish", () => {
    const route = req.route?.path
      ? `${req.baseUrl}${req.route.path}`
      : req.path;
    const durationSeconds =
      Number(process.hrtime.bigint() - start) / 1_000_000_000;

    httpRequestsTotal.inc({
      method: req.method,
      route,
      status_code: res.statusCode.toString(),
    });
    httpRequestDuration.observe(
      { method: req.method, route },
      durationSeconds
    );
  });

  next();
};

export const metricsHandler = async (
  _req: Request,
  res: Response
): Promise<void> => {
  res.setHeader("Content-Type", register.contentType);
  res.end(await register.metrics());
};
