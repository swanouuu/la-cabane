// Sert le croquis de l'arène en local : node serveur.mjs → http://127.0.0.1:8794/
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const fichier = path.join(path.dirname(fileURLToPath(import.meta.url)), "maquette.html");
http.createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
  res.end(`<!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"></head><body style="margin:0">${fs.readFileSync(fichier, "utf8")}</body></html>`);
}).listen(8794, "127.0.0.1", () => console.log("http://127.0.0.1:8794/"));
