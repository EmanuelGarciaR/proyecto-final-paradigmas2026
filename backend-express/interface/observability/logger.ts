import net from "node:net";

type LogLevel = "INFO" | "ERROR";

interface LogEntry {
  "@timestamp": string;
  level: LogLevel;
  message: string;
  application: string;
}

const application = process.env.APPLICATION_NAME ?? "chivas-rumberas-backend";
const logstashHost = process.env.LOGSTASH_HOST ?? "localhost";
const logstashPort = Number(process.env.LOGSTASH_PORT ?? 5001);

const sendToLogstash = (level: LogLevel, message: string): void => {
  const entry: LogEntry = {
    "@timestamp": new Date().toISOString(),
    level,
    message,
    application,
  };

  const socket = net.createConnection(
    { host: logstashHost, port: logstashPort },
    () => {
      socket.end(`${JSON.stringify(entry)}\n`);
    }
  );

  socket.setTimeout(1000);
  socket.on("timeout", () => socket.destroy());
  socket.on("error", (error) => {
    console.error(
      `[LOGSTASH] No se pudo enviar el log: ${error.message}`
    );
  });
};

export const logInfo = (message: string): void => {
  sendToLogstash("INFO", message);
};

export const logError = (message: string): void => {
  sendToLogstash("ERROR", message);
};
