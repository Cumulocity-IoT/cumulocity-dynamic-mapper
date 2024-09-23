// Adapted from http://indiegamr.com/generate-repeatable-random-numbers-in-js/
let _seed = Date.now();

export function valueOrDefault<T>(value: T | undefined, defaultValue: T) {
  return typeof value === 'undefined' ? defaultValue : value;
}

export function srand(seed) {
  _seed = seed;
}

export function rand(min, max) {
  const minx = valueOrDefault(min, 0);
  const maxx = valueOrDefault(max, 0);
  _seed = (_seed * 9301 + 49297) % 233280;
  return minx + (_seed / 233280) * (maxx - minx);
}

export function numbers(config) {
  const cfg = config || {};
  const min = valueOrDefault(cfg.min, 0);
  const max = valueOrDefault(cfg.max, 100);
  const from = valueOrDefault(cfg.from, []);
  const count = valueOrDefault(cfg.count, 8);
  const decimals = valueOrDefault(cfg.decimals, 8);
  const continuity = valueOrDefault(cfg.continuity, 1);
  const dfactor = Math.pow(10, decimals) || 0;
  const data = [];
  let i, value;

  for (i = 0; i < count; ++i) {
    value = (from[i] || 0) + rand(min, max);
    if (rand(0, 0) <= continuity) {
      data.push(Math.round(dfactor * value) / dfactor);
    } else {
      data.push(null);
    }
  }

  return data;
}

export function labels(config) {
  const cfg = config || {};
  const min = cfg.min || 0;
  const max = cfg.max || 100;
  const count = cfg.count || 8;
  const step = (max - min) / count;
  const decimals = cfg.decimals || 8;
  const dfactor = Math.pow(10, decimals) || 0;
  const prefix = cfg.prefix || '';
  const values = [];
  let i;

  for (i = min; i < max; i += step) {
    values.push(prefix + Math.round(dfactor * i) / dfactor);
  }

  return values;
}

const COLORS = [
  '#4dc9f6',
  '#f67019',
  '#f53794',
  '#537bc4',
  '#acc236',
  '#166a8f',
  '#00a950',
  '#58595b',
  '#8549ba'
];

export function color(index) {
  return COLORS[index % COLORS.length];
}

// export const CHART_COLORS = {
//   red: 'rgb(255, 99, 132)',
//   orange: 'rgb(255, 159, 64)',
//   yellow: 'rgb(255, 205, 86)',
//   green: 'rgb(75, 192, 192)',
//   blue: 'rgb(54, 162, 235)',
//   purple: 'rgb(153, 102, 255)',
//   grey: 'rgb(201, 203, 207)'
// };

export const CHART_COLORS = {
  red: 'red',
  orange: 'orange',
  yellow: 'yellow',
  green: 'green',
  blue: 'blue',
  purple: 'purple',
  grey: 'grey'
};

const NAMED_COLORS = [
  CHART_COLORS.red,
  CHART_COLORS.orange,
  CHART_COLORS.yellow,
  CHART_COLORS.green,
  CHART_COLORS.blue,
  CHART_COLORS.purple,
  CHART_COLORS.grey
];

export function namedColor(index) {
  return NAMED_COLORS[index % NAMED_COLORS.length];
}
