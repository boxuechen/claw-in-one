import { VScreenError, VScreenErrorCode, validateProducerId } from "./protocol.mjs";

function requireFunction(value, name) {
  if (typeof value !== "function") {
    throw new VScreenError(
      VScreenErrorCode.invalidRequest,
      `VScreen producer must implement ${name}`,
    );
  }
}

export class VScreenProducerRegistry {
  constructor() {
    this.entries = new Map();
    this.nextGeneration = 1;
  }

  register(producer) {
    if (!producer || typeof producer !== "object") {
      throw new VScreenError(VScreenErrorCode.invalidRequest, "VScreen producer is required");
    }
    const id = validateProducerId(producer.id);
    requireFunction(producer.ensureTarget, "ensureTarget");
    requireFunction(producer.placeWorkload, "placeWorkload");
    requireFunction(producer.verifyPresented, "verifyPresented");
    requireFunction(producer.closeTarget, "closeTarget");
    requireFunction(producer.prepareProbe, "prepareProbe");
    if (this.entries.has(id)) {
      throw new VScreenError(
        VScreenErrorCode.producerConflict,
        `VScreen producer ${id} is already registered`,
      );
    }
    const generation = this.nextGeneration++;
    const entry = Object.freeze({ id, generation, producer });
    this.entries.set(id, entry);
    let disposed = false;
    return Object.freeze({
      id,
      generation,
      dispose: () => {
        if (disposed) return false;
        disposed = true;
        if (this.entries.get(id) !== entry) return false;
        this.entries.delete(id);
        return true;
      },
    });
  }

  resolve(id) {
    return this.entries.get(id) ?? null;
  }

  clear() {
    this.entries.clear();
  }
}
