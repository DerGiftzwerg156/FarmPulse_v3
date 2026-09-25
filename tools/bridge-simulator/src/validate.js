// JSON-Schema validation of all bridge documents (schemas mirror docs/dev/bridge-protocol.md).
import Ajv2020 from 'ajv/dist/2020.js';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ajv = new Ajv2020({ allErrors: true, strict: false });
const load = (name) => ajv.compile(JSON.parse(readFileSync(join(here, '..', 'schema', name), 'utf8')));

export const validators = {
  farmFacts: load('farm_facts.schema.json'),
  marketContext: load('market_context.schema.json'),
  instructions: load('instructions.schema.json'),
  instructionsAck: load('instructions_ack.schema.json'),
};

/** Returns null when valid, otherwise a readable error string. */
export function validate(kind, doc) {
  const fn = validators[kind];
  if (fn(doc)) return null;
  return ajv.errorsText(fn.errors);
}
