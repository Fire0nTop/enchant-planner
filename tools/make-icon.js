// Draws the Enchant Planner icon as 32x32 pixel art and writes scaled PNGs.
// Usage: node tools/make-icon.js <out.png> <size> [<out2.png> <size2> ...]
const fs = require('fs');
const zlib = require('zlib');

const N = 32;

const PALETTE = {
	K: [20, 14, 30],     // outline
	H: [150, 156, 170],  // anvil highlight
	G: [96, 101, 114],   // anvil
	D: [58, 61, 72],     // anvil shadow
	W: [244, 236, 216],  // page
	w: [196, 184, 158],  // page text line
	P: [128, 44, 150],   // cover
	Q: [176, 86, 200],   // cover highlight
	R: [78, 22, 92],     // cover shadow
	M: [236, 150, 255],  // glint
	S: [255, 255, 255],  // sparkle core
	s: [160, 236, 255],  // sparkle arm
	X: [126, 252, 60],   // xp
};

// Left half of the open book (x 0..15); mirrored for the right half.
const BOOK_LEFT = [
	'                ', // 5
	'      KKKK      ', // 6
	'    KKWWWWKKK   ', // 7
	'   KWWWWWWWWWKK ', // 8
	'   KWwwwwWWWWWWw', // 9
	'   KWWWWWwwwwWWw', // 10
	'  KWwwwwwWWWWWWw', // 11
	'  KWWWWWWwwwwWWw', // 12
	'  KWwwwwwWWWWWWw', // 13
	' KQKWWWWWWWwwWWw', // 14
	' KQPKKWWWWWWWWWw', // 15
	' KQMPPKKKKWWWWWw', // 16
	'  KPPPMPPPPKKKKK', // 17
	'   KKRRPPPPPPPRR', // 18
	'     KKKKRRRRRRR', // 19
	'         KKKKKKK', // 20
];
const BOOK_TOP = 6;

const ANVIL = [
	'  KKKKKKKKKKKKKKKKKKKKKKKKKKK   ', // 22
	' KHHHHHHHHHHHHHHHHHHHHHHHHHHHK  ', // 23
	'KHHGGGGGGGGGGGGGGGGGGGGGGGGGGGK ', // 24
	' KKKDDDGGGGGGGGGGGGGGGGGGDDDKK  ', // 25
	'    KKKKKDGGGGGGGGGGGGDKKKK     ', // 26
	'        KDGGGGGGGGGGGGDK        ', // 27
	'       KKGGGGGGGGGGGGGGKK       ', // 28
	'     KKHHHHHHHHHHHHHHHHHHKK     ', // 29
	'     KDDDDDDDDDDDDDDDDDDDDK     ', // 30
];
const ANVIL_TOP = 22;

// sparkles: [x, y, big]
const SPARKLES = [[4, 3, true], [27, 4, true], [16, 1, false], [4, 27, false], [27, 27, false]];

function background(x, y) {
	// rounded tile, vertical purple gradient, 1px darker rim
	const r = 5;
	const cx = Math.min(Math.max(x, r), N - 1 - r);
	const cy = Math.min(Math.max(y, r), N - 1 - r);
	const d = Math.hypot(x - cx, y - cy);
	if (d > r + 0.5) {
		return null;
	}
	const t = y / (N - 1);
	const top = [74, 44, 118];
	const bottom = [26, 16, 46];
	let c = top.map((v, i) => Math.round(v + (bottom[i] - v) * t));
	// soft glow behind the book
	const glow = Math.max(0, 1 - Math.hypot(x - 15.5, y - 12) / 13);
	c = c.map((v, i) => Math.round(v + ([170, 90, 230][i] - v) * glow * 0.45));
	if (d > r - 0.6 || x === 0 || y === 0 || x === N - 1 || y === N - 1) {
		c = c.map(v => Math.round(v * 0.55));
	}
	return c;
}

function render() {
	const px = [];
	for (let y = 0; y < N; y++) {
		px.push([]);
		for (let x = 0; x < N; x++) {
			const bg = background(x, y);
			px[y].push(bg ? [...bg, 255] : [0, 0, 0, 0]);
		}
	}
	const put = (x, y, ch) => {
		if (ch !== ' ' && PALETTE[ch] && x >= 0 && y >= 0 && x < N && y < N) {
			px[y][x] = [...PALETTE[ch], 255];
		}
	};
	BOOK_LEFT.forEach((row, i) => {
		const y = BOOK_TOP + i;
		[...row].forEach((ch, x) => {
			put(x, y, ch);
			put(N - 1 - x, y, ch === 'Q' ? 'R' : ch); // right cover edge in shadow
		});
	});
	ANVIL.forEach((row, i) => [...row].forEach((ch, x) => put(x, ANVIL_TOP + i, ch)));
	for (const [x, y, big] of SPARKLES) {
		put(x, y, 'S');
		for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
			put(x + dx, y + dy, 's');
			if (big) {
				put(x + 2 * dx, y + 2 * dy, 's');
			}
		}
	}
	return px;
}

function crc32(buf) {
	let c, crc = 0xffffffff;
	for (let n = 0; n < buf.length; n++) {
		c = (crc ^ buf[n]) & 0xff;
		for (let k = 0; k < 8; k++) {
			c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
		}
		crc = (crc >>> 8) ^ c;
	}
	return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
	const len = Buffer.alloc(4);
	len.writeUInt32BE(data.length);
	const td = Buffer.concat([Buffer.from(type), data]);
	const crc = Buffer.alloc(4);
	crc.writeUInt32BE(crc32(td));
	return Buffer.concat([len, td, crc]);
}

function png(px, size) {
	const scale = size / N;
	const raw = Buffer.alloc(size * (size * 4 + 1));
	for (let y = 0; y < size; y++) {
		raw[y * (size * 4 + 1)] = 0;
		for (let x = 0; x < size; x++) {
			const p = px[Math.floor(y / scale)][Math.floor(x / scale)];
			p.forEach((v, i) => (raw[y * (size * 4 + 1) + 1 + x * 4 + i] = v));
		}
	}
	const ihdr = Buffer.alloc(13);
	ihdr.writeUInt32BE(size, 0);
	ihdr.writeUInt32BE(size, 4);
	ihdr[8] = 8; // bit depth
	ihdr[9] = 6; // RGBA
	return Buffer.concat([
		Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
		chunk('IHDR', ihdr),
		chunk('IDAT', zlib.deflateSync(raw, {level: 9})),
		chunk('IEND', Buffer.alloc(0)),
	]);
}

const px = render();
const args = process.argv.slice(2);
for (let i = 0; i < args.length; i += 2) {
	fs.writeFileSync(args[i], png(px, Number(args[i + 1])));
	console.log('wrote', args[i], args[i + 1] + 'px');
}
