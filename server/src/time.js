// All time decisions are made on the SERVER clock in the configured time zone,
// so a student cannot change the phone's clock to get around the window.
const config = require('./config');

const fmt = new Intl.DateTimeFormat('en-CA', {
  timeZone: config.timeZone,
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit',
  hourCycle: 'h23',
});

function localParts(date = new Date()) {
  const p = {};
  for (const { type, value } of fmt.formatToParts(date)) p[type] = value;
  const secondsOfDay = Number(p.hour) * 3600 + Number(p.minute) * 60 + Number(p.second);
  return {
    date: `${p.year}-${p.month}-${p.day}`,
    time: `${p.hour}:${p.minute}:${p.second}`,
    secondsOfDay,
  };
}

function windowStatus(date = new Date()) {
  const now = localParts(date);
  const open = now.secondsOfDay >= config.windowStart && now.secondsOfDay <= config.windowEnd;
  let state = 'OPEN';
  if (now.secondsOfDay < config.windowStart) state = 'NOT_STARTED';
  else if (now.secondsOfDay > config.windowEnd) state = 'CLOSED';
  return {
    open,
    state,
    start: config.windowStartText,
    end: config.windowEndText,
    today: now.date,
    serverTime: now.time,
    timeZone: config.timeZone,
    secondsToStart: open ? 0 : Math.max(0, config.windowStart - now.secondsOfDay),
    secondsToEnd: open ? config.windowEnd - now.secondsOfDay : 0,
  };
}

function isValidDate(s) {
  return /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(Date.parse(s + 'T00:00:00Z'));
}

// Inclusive list of YYYY-MM-DD strings between two dates
function dateRange(from, to) {
  const out = [];
  let d = new Date(from + 'T00:00:00Z');
  const end = new Date(to + 'T00:00:00Z');
  while (d <= end && out.length < 400) {
    out.push(d.toISOString().slice(0, 10));
    d = new Date(d.getTime() + 86400000);
  }
  return out;
}

function addDays(dateStr, n) {
  return new Date(Date.parse(dateStr + 'T00:00:00Z') + n * 86400000).toISOString().slice(0, 10);
}

module.exports = { localParts, windowStatus, isValidDate, dateRange, addDays };
