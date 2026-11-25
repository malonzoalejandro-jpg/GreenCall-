import 'dotenv/config';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import http from 'node:http';
import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import pg from 'pg';
import { Server as SocketServer } from 'socket.io';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 8080);
const DATABASE_URL = process.env.DATABASE_URL;
const JWT_SECRET = process.env.JWT_SECRET;
if (!DATABASE_URL || !JWT_SECRET) throw new Error('DATABASE_URL and JWT_SECRET are required.');
const pool = new pg.Pool({ connectionString: DATABASE_URL, ssl: process.env.PGSSL === 'disable' ? false : { rejectUnauthorized: false }, max: 15 });
const app = express();
const server = http.createServer(app);
const io = new SocketServer(server, { cors: { origin: process.env.CORS_ORIGIN?.split(',') || '*', methods: ['GET','POST'] }, maxHttpBufferSize: 2e6 });
app.disable('x-powered-by');
app.use(helmet());
app.use(cors({ origin: process.env.CORS_ORIGIN?.split(',') || '*', credentials: false }));
app.use(express.json({ limit: '2mb' }));
app.use('/v1/auth', rateLimit({ windowMs: 15 * 60 * 1000, limit: 30, standardHeaders: true, legacyHeaders: false }));

const query = (text, values = []) => pool.query(text, values);
const publicUser = u => ({ id: u.id, displayName: u.display_name, username: u.username });
const tokenFor = u => jwt.sign({ sub: u.id, username: u.username }, JWT_SECRET, { expiresIn: '30d', issuer: 'greencall' });
const auth = (req, res, next) => {
  try {
    const raw = req.headers.authorization || '';
    const token = raw.startsWith('Bearer ') ? raw.slice(7) : '';
    req.user = jwt.verify(token, JWT_SECRET, { issuer: 'greencall' });
    next();
  } catch { res.status(401).json({ error: 'Authentication required.' }); }
};
const asyncRoute = fn => (req,res,next) => Promise.resolve(fn(req,res,next)).catch(next);
const member = async (conversationId, userId) => (await query('SELECT 1 FROM conversation_members WHERE conversation_id=$1 AND user_id=$2', [conversationId,userId])).rowCount > 0;
const room = id => 'conversation:' + id;

app.get('/health', asyncRoute(async (_req,res) => {
  await query('SELECT 1');
  res.json({ ok: true, service: 'greencall-api', time: new Date().toISOString() });
}));

app.post('/v1/auth/register', asyncRoute(async (req,res) => {
  const displayName = String(req.body.displayName || '').trim();
  const username = String(req.body.username || '').trim().replace(/^@/,'').toLowerCase();
  const password = String(req.body.password || '');
  if (displayName.length < 2 || displayName.length > 80) return res.status(400).json({ error: 'Display name must be 2–80 characters.' });
  if (!/^[a-z0-9_]{3,24}$/.test(username)) return res.status(400).json({ error: 'Username must be 3–24 letters, numbers, or underscores.' });
  if (password.length < 10 || password.length > 128) return res.status(400).json({ error: 'Password must be at least 10 characters.' });
  const hash = await bcrypt.hash(password, 12);
  try {
    const { rows } = await query('INSERT INTO users(display_name,username,password_hash) VALUES($1,$2,$3) RETURNING *', [displayName,username,hash]);
    const user = publicUser(rows[0]);
    res.status(201).json({ user, token: tokenFor(rows[0]) });
  } catch (e) {
    if (e.code === '23505') return res.status(409).json({ error: 'That username is already taken.' });
    throw e;
  }
}));

app.post('/v1/auth/login', asyncRoute(async (req,res) => {
  const username = String(req.body.username || '').trim().replace(/^@/,'').toLowerCase();
  const password = String(req.body.password || '');
  const { rows } = await query('SELECT * FROM users WHERE username=$1', [username]);
  if (!rows[0] || !(await bcrypt.compare(password, rows[0].password_hash))) return res.status(401).json({ error: 'Username or password is incorrect.' });
  res.json({ user: publicUser(rows[0]), token: tokenFor(rows[0]) });
}));

app.get('/v1/me', auth, asyncRoute(async (req,res) => {
  const { rows } = await query('SELECT * FROM users WHERE id=$1', [req.user.sub]);
  if (!rows[0]) return res.status(404).json({ error: 'Account not found.' });
  res.json({ user: publicUser(rows[0]) });
}));

app.get('/v1/users/search', auth, asyncRoute(async (req,res) => {
  const term = String(req.query.name || '').trim();
  if (term.length < 2) return res.json({ users: [] });
  const { rows } = await query("SELECT id,display_name,username FROM users WHERE id<>$1 AND display_name ILIKE $2 ORDER BY display_name LIMIT 25", [req.user.sub, '%' + term.replace(/[\\%_]/g, '\\$&') + '%']);
  res.json({ users: rows.map(publicUser) });
}));

app.post('/v1/friend-requests/:friendId', auth, asyncRoute(async (req,res) => {
  const friendId = req.params.friendId;
  if (req.user.sub === friendId) return res.status(400).json({ error:'You cannot add yourself.' });
  if (!(await query('SELECT id FROM users WHERE id=$1', [friendId])).rowCount) return res.status(404).json({ error:'User not found.' });
  if ((await query('SELECT 1 FROM friendships WHERE user_id=$1 AND friend_id=$2', [req.user.sub,friendId])).rowCount) return res.status(409).json({ error:'You are already friends.' });
  if ((await query('SELECT id FROM friend_requests WHERE requester_id=$1 AND recipient_id=$2', [friendId,req.user.sub])).rowCount) return res.status(409).json({ error:'They already sent you a request. Accept it in Friend requests.' });
  const { rows } = await query('INSERT INTO friend_requests(requester_id,recipient_id) VALUES($1,$2) ON CONFLICT(requester_id,recipient_id) DO UPDATE SET created_at=NOW() RETURNING id', [req.user.sub,friendId]);
  res.status(201).json({ requestId:rows[0].id, pending:true });
}));

app.get('/v1/friend-requests', auth, asyncRoute(async (req,res) => {
  const { rows } = await query(`SELECT r.id,r.created_at,u.id AS user_id,u.display_name,u.username
    FROM friend_requests r JOIN users u ON u.id=r.requester_id
    WHERE r.recipient_id=$1 ORDER BY r.created_at DESC LIMIT 100`, [req.user.sub]);
  res.json({ requests:rows.map(r => ({ id:r.id, createdAt:r.created_at, user:publicUser({id:r.user_id,display_name:r.display_name,username:r.username}) })) });
}));

app.post('/v1/friend-requests/:requestId/accept', auth, asyncRoute(async (req,res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const { rows } = await client.query('SELECT requester_id,recipient_id FROM friend_requests WHERE id=$1 AND recipient_id=$2 FOR UPDATE', [req.params.requestId,req.user.sub]);
    if (!rows[0]) { await client.query('ROLLBACK'); return res.status(404).json({ error:'Friend request not found.' }); }
    const { requester_id: requesterId, recipient_id: recipientId } = rows[0];
    await client.query('INSERT INTO friendships(user_id,friend_id) VALUES($1,$2),($2,$1) ON CONFLICT DO NOTHING', [requesterId,recipientId]);
    await client.query('DELETE FROM friend_requests WHERE id=$1', [req.params.requestId]);
    await client.query('COMMIT');
    const { rows: users } = await client.query('SELECT id,display_name,username FROM users WHERE id=$1', [requesterId]);
    res.json({ accepted:true, friend:publicUser(users[0]) });
  } catch(error) { await client.query('ROLLBACK'); throw error; }
  finally { client.release(); }
}));

app.delete('/v1/friend-requests/:requestId', auth, asyncRoute(async (req,res) => {
  const removed = await query('DELETE FROM friend_requests WHERE id=$1 AND (recipient_id=$2 OR requester_id=$2)', [req.params.requestId,req.user.sub]);
  if (!removed.rowCount) return res.status(404).json({ error:'Friend request not found.' });
  res.json({ removed:true });
}));

app.delete('/v1/friends/:friendId', auth, asyncRoute(async (req,res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    await client.query('DELETE FROM friendships WHERE (user_id=$1 AND friend_id=$2) OR (user_id=$2 AND friend_id=$1)', [req.user.sub,req.params.friendId]);
    await client.query('DELETE FROM friend_requests WHERE (requester_id=$1 AND recipient_id=$2) OR (requester_id=$2 AND recipient_id=$1)', [req.user.sub,req.params.friendId]);
    await client.query('COMMIT');
    res.json({ removed:true });
  } catch(error) { await client.query('ROLLBACK'); throw error; }
  finally { client.release(); }
}));

app.get('/v1/friends', auth, asyncRoute(async (req,res) => {
  const { rows } = await query('SELECT u.id,u.display_name,u.username FROM friendships f JOIN users u ON u.id=f.friend_id WHERE f.user_id=$1 ORDER BY u.display_name', [req.user.sub]);
  res.json({ friends: rows.map(publicUser) });
}));

app.post('/v1/conversations/direct', auth, asyncRoute(async (req,res) => {
  const otherId = String(req.body.userId || '');
  const friendship = await query('SELECT 1 FROM friendships WHERE user_id=$1 AND friend_id=$2', [req.user.sub,otherId]);
  if (!friendship.rowCount) return res.status(403).json({ error: 'Add this person as a friend first.' });
  const { rows: found } = await query(`SELECT c.id FROM conversations c
    JOIN conversation_members a ON a.conversation_id=c.id AND a.user_id=$1
    JOIN conversation_members b ON b.conversation_id=c.id AND b.user_id=$2
    WHERE c.kind='direct' LIMIT 1`, [req.user.sub,otherId]);
  if (found[0]) return res.json({ conversationId: found[0].id });
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const { rows } = await client.query("INSERT INTO conversations(kind,created_by) VALUES('direct',$1) RETURNING id", [req.user.sub]);
    await client.query('INSERT INTO conversation_members(conversation_id,user_id) VALUES($1,$2),($1,$3)', [rows[0].id,req.user.sub,otherId]);
    await client.query('COMMIT');
    res.status(201).json({ conversationId: rows[0].id });
  } catch(e) { await client.query('ROLLBACK'); throw e; } finally { client.release(); }
}));

app.post('/v1/groups', auth, asyncRoute(async (req,res) => {
  const title = String(req.body.name || '').trim();
  const memberIds = [...new Set([req.user.sub, ...(Array.isArray(req.body.memberIds) ? req.body.memberIds.map(String) : [])])];
  if (title.length < 2 || title.length > 80 || memberIds.length < 2 || memberIds.length > 100) return res.status(400).json({ error: 'A group needs a name and at least one other member.' });
  const { rows: valid } = await query('SELECT id FROM users WHERE id=ANY($1::uuid[])', [memberIds]);
  if (valid.length !== memberIds.length) return res.status(400).json({ error: 'One or more members do not exist.' });
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const { rows } = await client.query("INSERT INTO conversations(kind,title,created_by) VALUES('group',$1,$2) RETURNING id,title", [title,req.user.sub]);
    for (const id of memberIds) await client.query('INSERT INTO conversation_members(conversation_id,user_id) VALUES($1,$2)', [rows[0].id,id]);
    await client.query('COMMIT');
    res.status(201).json({ group: { id: rows[0].id, name: rows[0].title, memberIds } });
  } catch(e) { await client.query('ROLLBACK'); throw e; } finally { client.release(); }
}));

app.post('/v1/groups/:id/members', auth, asyncRoute(async (req,res) => {
  const userId = String(req.body.userId || '');
  const { rows: groups } = await query("SELECT id FROM conversations WHERE id=$1 AND kind='group'", [req.params.id]);
  if (!groups[0] || !(await member(req.params.id,req.user.sub))) return res.status(403).json({ error:'Only group members can add people.' });
  if (!userId || userId === req.user.sub) return res.status(400).json({ error:'Choose another user.' });
  const friendship = await query('SELECT 1 FROM friendships WHERE user_id=$1 AND friend_id=$2', [req.user.sub,userId]);
  if (!friendship.rowCount) return res.status(403).json({ error:'You can only add your friends.' });
  const exists = await query('SELECT id FROM users WHERE id=$1', [userId]);
  if (!exists.rowCount) return res.status(404).json({ error:'User not found.' });
  await query('INSERT INTO conversation_members(conversation_id,user_id) VALUES($1,$2) ON CONFLICT DO NOTHING', [req.params.id,userId]);
  res.status(201).json({ added:true, userId });
}));

app.get('/v1/conversations', auth, asyncRoute(async (req,res) => {
  const { rows } = await query(`SELECT c.id,c.kind,c.title,c.created_at,
    (SELECT json_agg(json_build_object('id',u.id,'displayName',u.display_name,'username',u.username))
     FROM conversation_members cm JOIN users u ON u.id=cm.user_id WHERE cm.conversation_id=c.id) AS members,
    (SELECT json_build_object('id',m.id,'body',m.body,'type',m.message_type,'createdAt',m.created_at,'senderId',m.sender_id)
     FROM messages m WHERE m.conversation_id=c.id ORDER BY m.created_at DESC LIMIT 1) AS last_message
    FROM conversations c JOIN conversation_members cm ON cm.conversation_id=c.id
    WHERE cm.user_id=$1 ORDER BY c.created_at DESC`, [req.user.sub]);
  res.json({ conversations: rows });
}));

app.get('/v1/conversations/:id/messages', auth, asyncRoute(async (req,res) => {
  if (!(await member(req.params.id,req.user.sub))) return res.status(403).json({ error: 'Not a conversation member.' });
  const before = req.query.before ? new Date(String(req.query.before)) : new Date();
  const { rows } = await query('SELECT id,conversation_id,sender_id,body,message_type,created_at FROM messages WHERE conversation_id=$1 AND created_at<$2 ORDER BY created_at DESC LIMIT 100', [req.params.id,before]);
  res.json({ messages: rows.reverse() });
}));

app.post('/v1/conversations/:id/messages', auth, asyncRoute(async (req,res) => {
  if (!(await member(req.params.id,req.user.sub))) return res.status(403).json({ error: 'Not a conversation member.' });
  const body = String(req.body.body || '').trim();
  if (!body || body.length > 10000) return res.status(400).json({ error: 'Message must be 1–10000 characters.' });
  const { rows } = await query("INSERT INTO messages(conversation_id,sender_id,body,message_type) VALUES($1,$2,$3,'text') RETURNING *", [req.params.id,req.user.sub,body]);
  const message = rows[0];
  const { rows: recipients } = await query('SELECT user_id FROM conversation_members WHERE conversation_id=$1 AND user_id<>$2', [req.params.id,req.user.sub]);
  for (const r of recipients) await query('INSERT INTO delivery_receipts(message_id,user_id) VALUES($1,$2) ON CONFLICT DO NOTHING', [message.id,r.user_id]);
  io.to(room(req.params.id)).emit('message:new', message);
  res.status(201).json({ message });
}));

app.post('/v1/conversations/:id/voicemail', auth, asyncRoute(async (req,res) => {
  const conversationId = req.params.id;
  if (!(await member(conversationId,req.user.sub))) return res.status(403).json({ error:'Not a conversation member.' });
  const audioBase64 = String(req.body.audioBase64 || '');
  const durationMs = Math.max(0,Math.min(25000,Number(req.body.durationMs)||0));
  if (audioBase64.length < 20 || audioBase64.length > 1500000) return res.status(400).json({ error:'Voice note is empty or too large (25-second limit).' });
  const { rows: recipients } = await query('SELECT user_id FROM conversation_members WHERE conversation_id=$1 AND user_id<>$2', [conversationId,req.user.sub]);
  const saved = [];
  for (const recipient of recipients) {
    const { rows } = await query(
      'INSERT INTO voice_mails(conversation_id,sender_id,recipient_id,audio_base64,duration_ms) VALUES($1,$2,$3,$4,$5) RETURNING id,created_at',
      [conversationId,req.user.sub,recipient.user_id,audioBase64,durationMs]
    );
    io.to('user:' + recipient.user_id).emit('voicemail:new', { id:rows[0].id,conversationId,senderId:req.user.sub,createdAt:rows[0].created_at });
    saved.push(rows[0].id);
  }
  res.status(201).json({ saved: saved.length });
}));

app.get('/v1/voicemails', auth, asyncRoute(async (req,res) => {
  const { rows } = await query(`SELECT v.id,v.conversation_id,v.sender_id,u.display_name AS sender_name,v.duration_ms,v.created_at
    FROM voice_mails v JOIN users u ON u.id=v.sender_id WHERE v.recipient_id=$1 AND v.listened_at IS NULL ORDER BY v.created_at DESC LIMIT 50`, [req.user.sub]);
  res.json({ voicemails: rows });
}));

app.post('/v1/voicemails', auth, asyncRoute(async (req,res) => {
  const { conversationId, recipientId, audioBase64, durationMs = 0 } = req.body;
  if (!(await member(conversationId,req.user.sub)) || !(await member(conversationId,recipientId))) return res.status(403).json({ error: 'Both users must belong to this conversation.' });
  if (typeof audioBase64 !== 'string' || audioBase64.length < 20 || audioBase64.length > 1_500_000) return res.status(400).json({ error: 'Invalid or oversized voice message.' });
  const { rows } = await query('INSERT INTO voice_mails(conversation_id,sender_id,recipient_id,audio_base64,duration_ms) VALUES($1,$2,$3,$4,$5) RETURNING id,created_at', [conversationId,req.user.sub,recipientId,audioBase64,Math.max(0,Math.min(300000,Number(durationMs)||0))]);
  io.to('user:' + recipientId).emit('voicemail:new', { id: rows[0].id, conversationId, senderId: req.user.sub, createdAt: rows[0].created_at });
  res.status(201).json({ voicemailId: rows[0].id });
}));

app.get('/v1/voicemails/:id/audio', auth, asyncRoute(async (req,res) => {
  const { rows } = await query('SELECT audio_base64 FROM voice_mails WHERE id=$1 AND recipient_id=$2', [req.params.id,req.user.sub]);
  if (!rows[0]) return res.status(404).json({ error: 'Voicemail not found.' });
  await query('UPDATE voice_mails SET listened_at=COALESCE(listened_at,NOW()) WHERE id=$1', [req.params.id]);
  res.json({ audioBase64: rows[0].audio_base64 });
}));

io.use((socket,next) => {
  try { socket.user = jwt.verify(socket.handshake.auth?.token || '', JWT_SECRET, { issuer: 'greencall' }); next(); }
  catch { next(new Error('unauthorized')); }
});
const callSessions = new Map();
io.on('connection', socket => {
  const userId = socket.user.sub;
  socket.data.muted = false;
  socket.join('user:' + userId);

  socket.on('voice:mute', value => {
    socket.data.muted = Boolean(value?.muted);
  });

  socket.on('conversation:join', async ({ conversationId } = {}, ack = () => {}) => {
    try {
      if (!(await member(conversationId,userId))) return ack({ ok:false,error:'Not a member.' });
      socket.join(room(conversationId));
      ack({ ok:true });
    } catch { ack({ ok:false,error:'Unable to join.' }); }
  });
  socket.on('conversation:leave', ({ conversationId } = {}) => socket.leave(room(conversationId)));

  socket.on('voice:start', async (packet = {}, ack = () => {}) => {
    try {
      const conversationId = String(packet.conversationId || '');
      if (!conversationId || !(await member(conversationId,userId))) return ack({ ok:false,error:'Not a conversation member.' });
      const { rows } = await query('SELECT user_id FROM conversation_members WHERE conversation_id=$1 AND user_id<>$2', [conversationId,userId]);
      const liveRecipients = [];
      for (const row of rows) {
        const sockets = await io.in('user:' + row.user_id).fetchSockets();
        if (sockets.some(s => !s.data.muted && s.rooms.has(room(conversationId)))) {
          liveRecipients.push(row.user_id);
          for (const target of sockets) if (!target.data.muted && target.rooms.has(room(conversationId))) target.emit('voice:start', { conversationId, senderId:userId });
        }
      }
      socket.data.liveVoiceRecipients = socket.data.liveVoiceRecipients || {};
      socket.data.liveVoiceRecipients[conversationId] = liveRecipients;
      ack({ ok:true, liveRecipients });
    } catch { ack({ ok:false,error:'Could not start voice.' }); }
  });

  socket.on('voice:chunk', async (packet = {}) => {
    try {
      const conversationId = String(packet.conversationId || '');
      const data = String(packet.data || '');
      if (!conversationId || data.length > 16000 || !(await member(conversationId,userId))) return;
      const sockets = await io.in(room(conversationId)).fetchSockets();
      for (const target of sockets) {
        if (target.id !== socket.id && !target.data.muted) {
          target.emit('voice:chunk', { conversationId, senderId:userId, data });
        }
      }
    } catch {}
  });

  socket.on('voice:finish', async (packet = {}, ack = () => {}) => {
    try {
      const conversationId = String(packet.conversationId || '');
      const audioBase64 = String(packet.audioBase64 || '');
      const durationMs = Math.max(0,Math.min(25000,Number(packet.durationMs)||0));
      if (!conversationId || !(await member(conversationId,userId))) return ack({ ok:false,error:'Not a conversation member.' });
      if (audioBase64.length < 20 || audioBase64.length > 1500000) return ack({ ok:false,error:'Voice note is empty or too large (25-second limit).' });
      const { rows } = await query('SELECT user_id FROM conversation_members WHERE conversation_id=$1 AND user_id<>$2', [conversationId,userId]);
      const saved = [];
      for (const row of rows) {
        const sockets = await io.in('user:' + row.user_id).fetchSockets();
        const live = sockets.some(s => !s.data.muted && s.rooms.has(room(conversationId)));
        const wasLive = (socket.data.liveVoiceRecipients?.[conversationId] || []).includes(row.user_id);
        if (!live || !wasLive) {
          const { rows: inserted } = await query(
            'INSERT INTO voice_mails(conversation_id,sender_id,recipient_id,audio_base64,duration_ms) VALUES($1,$2,$3,$4,$5) RETURNING id,created_at',
            [conversationId,userId,row.user_id,audioBase64,durationMs]
          );
          io.to('user:' + row.user_id).emit('voicemail:new', { id:inserted[0].id,conversationId,senderId:userId,createdAt:inserted[0].created_at });
          saved.push(row.user_id);
        }
      }
      const sockets = await io.in(room(conversationId)).fetchSockets();
      for (const target of sockets) if (target.id !== socket.id && !target.data.muted && target.rooms.has(room(conversationId))) target.emit('voice:end', { conversationId,senderId:userId });
      if (socket.data.liveVoiceRecipients) delete socket.data.liveVoiceRecipients[conversationId];
      ack({ ok:true, voicemailRecipients:saved });
    } catch { ack({ ok:false,error:'Could not save voice message.' }); }
  });
  socket.on('call:invite', async (packet = {}) => {
    try {
      const callId = String(packet.callId || '');
      const conversationId = String(packet.conversationId || '');
      const recipientId = String(packet.recipientId || '');
      if (!callId || !conversationId || !recipientId || recipientId === userId ||
          !(await member(conversationId,userId)) || !(await member(conversationId,recipientId))) {
        socket.emit('call:error', { message:'This friend is not in the conversation.' });
        return;
      }
      const call = { callId, conversationId, callerId:userId, recipientId, video:Boolean(packet.video) };
      callSessions.set(callId, call);
      const targets = await io.in('user:' + recipientId).fetchSockets();
      if (!targets.length) {
        socket.emit('call:error', { message:'Your friend is offline. Calls need both devices connected.' });
        callSessions.delete(callId);
        return;
      }
      io.to('user:' + recipientId).emit('call:incoming', {
        callId, conversationId, callerId:userId, callerName:socket.user.username, video:call.video
      });
    } catch { socket.emit('call:error', { message:'Could not start the call.' }); }
  });

  socket.on('call:accept', async (packet = {}) => {
    const call = callSessions.get(String(packet.callId || ''));
    if (!call || call.recipientId !== userId) return;
    socket.emit('call:accepted', { callId:call.callId });
    io.to('user:' + call.callerId).emit('call:accepted', { callId:call.callId });
  });

  socket.on('call:signal', async (packet = {}) => {
    const call = callSessions.get(String(packet.callId || ''));
    if (!call || (call.callerId !== userId && call.recipientId !== userId)) return;
    const type = String(packet.type || '');
    if (!['offer','answer','ice'].includes(type)) return;
    const otherId = call.callerId === userId ? call.recipientId : call.callerId;
    const signal = { callId:call.callId, type };
    if (type === 'ice') {
      signal.candidate = String(packet.candidate || '').slice(0, 4096);
      signal.sdpMid = String(packet.sdpMid || '').slice(0, 128);
      signal.sdpMLineIndex = Number(packet.sdpMLineIndex) || 0;
    } else {
      signal.sdp = String(packet.sdp || '').slice(0, 100000);
      if (!signal.sdp) return;
    }
    io.to('user:' + otherId).emit('call:signal', signal);
  });

  socket.on('call:end', (packet = {}) => {
    const callId = String(packet.callId || '');
    const call = callSessions.get(callId);
    if (!call || (call.callerId !== userId && call.recipientId !== userId)) return;
    const otherId = call.callerId === userId ? call.recipientId : call.callerId;
    io.to('user:' + otherId).emit('call:ended', { callId });
    callSessions.delete(callId);
  });

  socket.on('disconnect', () => {});
});

app.use((err,_req,res,_next) => {
  console.error(err);
  res.status(500).json({ error: 'Server error.' });
});

async function start() {
  const schema = fs.readFileSync(path.join(__dirname,'..','schema.sql'),'utf8');
  await query(schema);
  server.listen(PORT,'0.0.0.0',() => console.log(`GreenCall API listening on ${PORT}`));
}
start().catch(err => { console.error(err); process.exit(1); });
