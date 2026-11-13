// Sert l'aperçu 3D de Minato 3 en local : node serveur.mjs → http://127.0.0.1:8795/
// POST /capture?nom=x.png enregistre une image du rendu (data URL PNG) dans captures/, pour la planche.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const TYPES = { ".html": "text/html; charset=utf-8", ".mjs": "text/javascript", ".js": "text/javascript", ".png": "image/png", ".json": "application/json" };
http.createServer((req, res) => {
  const url = new URL(req.url, "http://x");
  if (req.method === "POST" && url.pathname === "/capture") {
    const nom = path.basename(url.searchParams.get("nom") || "capture.png");
    const morceaux = [];
    req.on("data", (d) => morceaux.push(d));
    req.on("end", () => {
      const texte = Buffer.concat(morceaux).toString();
      fs.mkdirSync(path.join(ICI, "captures"), { recursive: true });
      fs.writeFileSync(path.join(ICI, "captures", nom), Buffer.from(texte.replace(/^data:image\/png;base64,/, ""), "base64"));
      res.writeHead(200);
      res.end("ok");
    });
    return;
  }
  // le son du flash de Minato : celui du pack privé (voix/, hors du dépôt)
  if (url.pathname === "/son/minato.flash.ogg") {
    const son = path.join(ICI, "..", "..", "..", "voix", "effets", "minato.flash.ogg");
    if (!fs.existsSync(son)) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { "Content-Type": "audio/ogg" });
    fs.createReadStream(son).pipe(res);
    return;
  }
  const nom = decodeURIComponent(url.pathname).replace(/^\/+/, "") || "index.html";
  const fichier = path.join(ICI, nom);
  if (!fichier.startsWith(ICI) || !fs.existsSync(fichier) || fs.statSync(fichier).isDirectory()) {
    res.writeHead(404);
    res.end("introuvable");
    return;
  }
  res.writeHead(200, { "Content-Type": TYPES[path.extname(fichier)] ?? "application/octet-stream", "Cache-Control": "no-store" });
  fs.createReadStream(fichier).pipe(res);
}).listen(8795, "127.0.0.1", () => console.log("http://127.0.0.1:8795/"));
