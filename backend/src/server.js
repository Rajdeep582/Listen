import 'dotenv/config';
import express from 'express';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { MongoClient, ObjectId } from 'mongodb';

const { MONGODB_URI, JWT_SECRET } = process.env;
if (!MONGODB_URI || !JWT_SECRET || JWT_SECRET.length < 32) {
  throw new Error('Set MONGODB_URI and a JWT_SECRET of at least 32 characters.');
}
const client = new MongoClient(MONGODB_URI, { serverSelectionTimeoutMS: 10000 });
await client.connect();
const db = client.db('listen_app');
const users = db.collection('users');
const favorites = db.collection('favorites');
const listening = db.collection('listening_history');
const searches = db.collection('search_history');
const preferences = db.collection('user_preferences');
await Promise.all([
  users.createIndex({ usernameKey: 1 }, { unique: true }),
  favorites.createIndex({ userId: 1, songId: 1 }, { unique: true }),
  favorites.createIndex({ userId: 1, addedAt: -1 }),
  listening.createIndex({ userId: 1, songId: 1 }, { unique: true }),
  listening.createIndex({ userId: 1, listenedAt: -1 }),
  searches.createIndex({ userId: 1, queryKey: 1 }, { unique: true }),
  searches.createIndex({ userId: 1, searchedAt: -1 }),
  preferences.createIndex({ userId: 1 }, { unique: true }),
]);

const app = express();
app.disable('x-powered-by');
app.use(helmet());
app.use(express.json({ limit: '32kb' }));
app.use('/v1/auth', rateLimit({ windowMs: 15 * 60 * 1000, limit: 30, standardHeaders: true, legacyHeaders: false }));
const fail = (res, status, error) => res.status(status).json({ error });
const asyncRoute = handler => (req, res, next) => Promise.resolve(handler(req, res, next)).catch(next);
const userId = req => new ObjectId(req.auth.sub);
const publicUser = u => ({ id: u._id.toString(), username: u.username, createdAt: u.createdAt, lastLoginAt: u.lastLoginAt });
function auth(req, res, next) {
  const token = req.headers.authorization?.match(/^Bearer (.+)$/i)?.[1];
  try { req.auth = jwt.verify(token, JWT_SECRET, { algorithms: ['HS256'] }); next(); }
  catch { fail(res, 401, 'Sign in to continue.'); }
}
function songInput(body) {
  const { songId, title, artist, album = '', artwork = '' } = body ?? {};
  if (typeof songId !== 'string' || !songId.trim() || songId.length > 200 ||
      typeof title !== 'string' || !title.trim() || title.length > 300 ||
      typeof artist !== 'string' || artist.length > 300 || typeof album !== 'string' || album.length > 300 ||
      typeof artwork !== 'string' || artwork.length > 1000) return null;
  return { songId: songId.trim(), title: title.trim(), artist: artist.trim(), album: album.trim(), artwork };
}

app.get('/health', (_req, res) => res.json({ ok: true }));
app.post('/v1/auth/signup', asyncRoute(async (req, res) => {
  const username = typeof req.body?.username === 'string' ? req.body.username.trim() : '';
  const password = req.body?.password;
  if (!/^[A-Za-z0-9_]{3,24}$/.test(username)) return fail(res, 400, 'Username must be 3–24 letters, numbers, or underscores.');
  if (typeof password !== 'string' || password.length < 6 || password.length > 128) return fail(res, 400, 'Password must be 6–128 characters.');
  const now = new Date();
  try {
    const doc = { username, usernameKey: username.toLowerCase(), passwordHash: await bcrypt.hash(password, 12), createdAt: now, lastLoginAt: now, profile: {} };
    const result = await users.insertOne(doc);
    const token = jwt.sign({ sub: result.insertedId.toString() }, JWT_SECRET, { algorithm: 'HS256', expiresIn: '30d' });
    res.status(201).json({ token, user: publicUser({ ...doc, _id: result.insertedId }) });
  } catch (e) { if (e.code === 11000) return fail(res, 409, 'That username is already taken.'); throw e; }
}));
app.post('/v1/auth/signin', asyncRoute(async (req, res) => {
  const username = typeof req.body?.username === 'string' ? req.body.username.trim() : '';
  const password = req.body?.password;
  const user = await users.findOne({ usernameKey: username.toLowerCase() });
  if (!user || typeof password !== 'string' || !(await bcrypt.compare(password, user.passwordHash))) return fail(res, 401, 'Username or password is incorrect.');
  user.lastLoginAt = new Date(); await users.updateOne({ _id: user._id }, { $set: { lastLoginAt: user.lastLoginAt } });
  const token = jwt.sign({ sub: user._id.toString() }, JWT_SECRET, { algorithm: 'HS256', expiresIn: '30d' });
  res.json({ token, user: publicUser(user) });
}));
app.get('/v1/me', auth, asyncRoute(async (req, res) => {
  const user = await users.findOne({ _id: userId(req) }, { projection: { passwordHash: 0, usernameKey: 0 } });
  if (!user) return fail(res, 401, 'Account not found.');
  res.json({ user: publicUser(user) });
}));
app.get('/v1/favorites', auth, asyncRoute(async (req, res) => res.json(await favorites.find({ userId: userId(req) }).sort({ addedAt: -1 }).limit(500).toArray())));
app.put('/v1/favorites', auth, asyncRoute(async (req, res) => {
  const song = songInput(req.body); if (!song) return fail(res, 400, 'Invalid song information.');
  await favorites.updateOne({ userId: userId(req), songId: song.songId }, { $setOnInsert: { ...song, userId: userId(req), addedAt: new Date() } }, { upsert: true });
  res.status(204).end();
}));
app.delete('/v1/favorites/:songId', auth, asyncRoute(async (req, res) => { await favorites.deleteOne({ userId: userId(req), songId: req.params.songId }); res.status(204).end(); }));
app.get('/v1/history', auth, asyncRoute(async (req, res) => res.json(await listening.find({ userId: userId(req) }).sort({ listenedAt: -1 }).limit(15).toArray())));
app.post('/v1/history', auth, asyncRoute(async (req, res) => {
  const song = songInput(req.body); if (!song) return fail(res, 400, 'Invalid song information.');
  const uid = userId(req); const listenedAt = new Date();
  await listening.updateOne({ userId: uid, songId: song.songId }, { $set: { ...song, listenedAt } }, { upsert: true });
  const overflow = await listening.find({ userId: uid }).sort({ listenedAt: -1 }).skip(15).project({ _id: 1 }).toArray();
  if (overflow.length) await listening.deleteMany({ _id: { $in: overflow.map(x => x._id) }, userId: uid });
  res.status(204).end();
}));
app.get('/v1/searches', auth, asyncRoute(async (req, res) => res.json(await searches.find({ userId: userId(req) }).sort({ searchedAt: -1 }).limit(20).toArray())));
app.post('/v1/searches', auth, asyncRoute(async (req, res) => {
  const query = typeof req.body?.query === 'string' ? req.body.query.trim().slice(0, 200) : '';
  if (!query) return res.status(204).end();
  const uid = userId(req); const queryKey = query.toLowerCase();
  await searches.updateOne({ userId: uid, queryKey }, { $set: { query, searchedAt: new Date() } }, { upsert: true });
  const overflow = await searches.find({ userId: uid }).sort({ searchedAt: -1 }).skip(20).project({ _id: 1 }).toArray();
  if (overflow.length) await searches.deleteMany({ _id: { $in: overflow.map(x => x._id) }, userId: uid });
  res.status(204).end();
}));
app.get('/v1/preferences', auth, asyncRoute(async (req, res) => res.json((await preferences.findOne({ userId: userId(req) }))?.values ?? {})));
app.put('/v1/preferences', auth, asyncRoute(async (req, res) => {
  const allowed = ['onlineEnabled', 'musicTheme', 'reduceMotion'];
  const values = Object.fromEntries(allowed.filter(k => typeof req.body?.[k] === 'boolean' || (k === 'musicTheme' && Number.isInteger(req.body?.[k]))).map(k => [k, req.body[k]]));
  await preferences.updateOne({ userId: userId(req) }, { $set: { values, updatedAt: new Date() } }, { upsert: true });
  res.status(204).end();
}));
app.use((err, _req, res, _next) => { console.error('Request failed:', err.message); res.status(500).json({ error: 'Service error. Please try again.' }); });
const port = Number(process.env.PORT || 8080);
app.listen(port, '0.0.0.0', () => console.log(`Listen API listening on ${port}; database listen_app`));
process.on('SIGTERM', async () => { await client.close(); process.exit(0); });
