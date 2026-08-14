import express from 'express';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = 3000;

const distPath = path.join(__dirname, 'dist');
const wwwPath = path.join(__dirname, 'www');
const staticPath = fs.existsSync(distPath) && process.env.NODE_ENV === 'production' ? distPath : wwwPath;

app.use(express.static(staticPath));

// Health check endpoint
app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', app: 'Unifest' });
});

// Fallback to index.html for SPA routing
app.use((req, res) => {
  const indexPath = path.join(staticPath, 'index.html');
  if (fs.existsSync(indexPath)) {
    res.sendFile(indexPath);
  } else {
    res.sendFile(path.join(wwwPath, 'index.html'));
  }
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`Unifest server running on http://0.0.0.0:${PORT}`);
});
