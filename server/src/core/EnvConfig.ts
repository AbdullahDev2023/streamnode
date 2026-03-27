/**
 * core/EnvConfig.ts — Schema-based environment variable validator.
 */

export interface EnvFieldSchema {
    type:         'string' | 'int' | 'float' | 'bool';
    default?:     unknown;
    required?:    boolean;
    validate?:    (v: unknown) => true | string;
    description?: string;
}

type EnvSchema = Record<string, EnvFieldSchema>;
type EnvResult = Record<string, unknown>;

const COERCERS: Record<string, (v: string) => unknown> = {
    string: (v) => v,
    int: (v) => {
        const n = parseInt(v, 10);
        if (isNaN(n)) throw new Error(`expected integer, got "${v}"`);
        return n;
    },
    float: (v) => {
        const n = parseFloat(v);
        if (isNaN(n)) throw new Error(`expected float, got "${v}"`);
        return n;
    },
    bool: (v) => {
        if (v === 'true'  || (v as unknown) === true)  return true;
        if (v === 'false' || (v as unknown) === false) return false;
        throw new Error(`expected 'true' or 'false', got "${v}"`);
    },
};

export class EnvConfig {
    static applySchema(schema: EnvSchema, source: NodeJS.ProcessEnv = process.env): EnvResult {
        const result: EnvResult = {};
        const errors: string[]  = [];

        for (const [key, field] of Object.entries(schema)) {
            const isRequired = field.required || field.default === undefined;
            const raw        = source[key];

            if (raw === undefined || raw === '') {
                if (isRequired) {
                    const hint = field.description ? ` (${field.description})` : '';
                    errors.push(`  ${key}: required but not set${hint}`);
                    continue;
                }
                result[key] = field.default;
                continue;
            }

            const coerce = COERCERS[field.type];
            if (!coerce) {
                errors.push(`  ${key}: unknown type "${field.type}" in schema`);
                continue;
            }

            let coerced: unknown;
            try {
                coerced = coerce(raw);
            } catch (e: unknown) {
                errors.push(`  ${key}: ${(e as Error).message}`);
                continue;
            }

            if (typeof field.validate === 'function') {
                const verdict = field.validate(coerced);
                if (verdict !== true) {
                    errors.push(`  ${key}: validation failed — ${verdict}`);
                    continue;
                }
            }

            result[key] = coerced;
        }

        if (errors.length) {
            throw new Error(`EnvConfig validation failed:\n${errors.join('\n')}`);
        }

        return Object.freeze(result);
    }

    static mergeAndApply(...schemas: EnvSchema[]): EnvResult {
        const merged = Object.assign({}, ...schemas) as EnvSchema;
        return EnvConfig.applySchema(merged);
    }
}
