// Sert le croquis de la salle de muscu en local : node serveur.mjs → http://127.0.0.1:8800/
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const fichier = path.join(path.dirname(fileURLToPath(import.meta.url)), "maquette.html");
http.createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
  res.end(fs.readFileSync(fichier, "utf8"));
}).listen(8800, "127.0.0.1", () => console.log("http://127.0.0.1:8800/"));
