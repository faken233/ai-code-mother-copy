'use strict';

/**
 * 声明式 DDL 生成：schema.json → CREATE TABLE / ALTER TABLE
 * 只有这里能产出表结构语句，AI 无法写入任何原生 SQL 到数据面。
 */

const TYPE_MAP = {
  string: (c) => `VARCHAR(${Math.min(Math.max(Number(c.max) || 255, 1), 2048)})`,
  text: () => 'TEXT',
  int: () => 'INT',
  bigint: () => 'BIGINT',
  decimal: (c) => {
    const [p, s] = Array.isArray(c.precision) ? c.precision : [12, 2];
    return `DECIMAL(${Math.min(Number(p) || 12, 65)},${Math.min(Number(s) || 2, 30)})`;
  },
  bool: () => 'TINYINT(1)',
  datetime: () => 'DATETIME',
  date: () => 'DATE',
  json: () => 'JSON',
  enum: (c) => {
    const maxLen = Math.max(...(c.values || ['']).map((v) => String(v).length), 16);
    return `VARCHAR(${Math.min(maxLen, 255)})`;
  }
};

const SYSTEM_COLUMNS = new Set(['id', 'user_id', 'created_at', 'updated_at']);

function columnDdl(name, c) {
  const build = TYPE_MAP[c.type];
  if (!build) {
    throw new Error(`schema.json 不支持的字段类型: ${name} -> ${c.type}`);
  }
  const nullability = c.required ? 'NOT NULL' : 'NULL';
  return `\`${name}\` ${build(c)} ${nullability}`;
}

function indexName(table, columns) {
  return `idx_${table}_${columns.join('_')}`.slice(0, 60);
}

function createTableDdl(table, meta) {
  const parts = ['`id` BIGINT NOT NULL AUTO_INCREMENT'];
  for (const [name, c] of Object.entries(meta.columns || {})) {
    if (SYSTEM_COLUMNS.has(name)) {
      throw new Error(`schema.json 不要声明系统字段: ${table}.${name}（平台自动补）`);
    }
    parts.push(columnDdl(name, c));
  }
  if (meta.owner !== false) {
    parts.push('`user_id` BIGINT NULL');
  }
  parts.push('`created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP');
  parts.push('`updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
  parts.push('PRIMARY KEY (`id`)');

  const indexes = Array.isArray(meta.indexes) ? meta.indexes : [];
  const declared = new Set();

  for (const cols of indexes) {
    if (!Array.isArray(cols) || cols.length === 0) continue;
    declared.add(cols.join(','));
    parts.push(`KEY \`${indexName(table, cols)}\` (${cols.map((c) => `\`${c}\``).join(', ')})`);
  }
  // 归属列默认加索引：owner 表几乎都要按 user_id 过滤
  if (meta.owner !== false && ![...declared].some((k) => k.split(',')[0] === 'user_id')) {
    parts.push(`KEY \`${indexName(table, ['user_id'])}\` (\`user_id\`)`);
  }

  return `CREATE TABLE IF NOT EXISTS \`${table}\` (\n  ${parts.join(',\n  ')}\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`;
}

/**
 * 增量加列。已有数据的表加 NOT NULL 列会失败，因此增量列一律先允许 NULL，
 * 由后续数据填充后再收紧（这是 v1 的明确取舍：先能演进，不丢数据）。
 */
function addColumnDdl(table, name, c) {
  const build = TYPE_MAP[c.type];
  if (!build) {
    throw new Error(`schema.json 不支持的字段类型: ${name} -> ${c.type}`);
  }
  return `ALTER TABLE \`${table}\` ADD COLUMN \`${name}\` ${build(c)} NULL`;
}

function createIndexDdl(table, columns) {
  return `CREATE INDEX \`${indexName(table, columns)}\` ON \`${table}\` (${columns.map((c) => `\`${c}\``).join(', ')})`;
}

module.exports = {
  TYPE_MAP,
  SYSTEM_COLUMNS,
  columnDdl,
  createTableDdl,
  addColumnDdl,
  createIndexDdl,
  indexName
};
