#!/usr/bin/env python3
"""Write a deterministic tiny Llama GGUF with synthetic weights, no downloads.

It emits a repeated 'probe' token. This tests real tokenizer/model/decode paths,
not translation quality. The fixture has never been trained on any user content.
"""
import array
from pathlib import Path
import struct
import sys


def string(value):
    data = value.encode('utf-8')
    return struct.pack('<Q', len(data)) + data


def generate(destination, terminating=False, context_sensitive=False):
    # GGUF types: UINT32=4, FLOAT32=6, BOOL=7, STRING=8, ARRAY=9.
    tokens = ['<unk>', '<s>', '</s>'] + [f'<0x{i:02X}>' for i in range(256)] + ['▁probe']
    n_vocab, embedding, hidden = len(tokens), 16, 32
    def kv(key, kind, value):
        if kind == 4: data = struct.pack('<I', value)
        elif kind == 6: data = struct.pack('<f', value)
        elif kind == 7: data = bytes([bool(value)])
        elif kind == 8: data = string(value)
        elif kind == 9:
            subtype, values = value
            data = struct.pack('<IQ', subtype, len(values))
            data += b''.join(string(x) if subtype == 8 else struct.pack('<f' if subtype == 6 else '<i' if subtype == 5 else '<I', x) for x in values)
        else: raise ValueError(kind)
        return string(key) + struct.pack('<I', kind) + data
    metadata = [
        kv('general.architecture', 8, 'llama'),
        kv('general.name', 8, 'Synthetic diagnostic fixture'),
        kv('general.file_type', 4, 0),
        kv('general.alignment', 4, 32),
        kv('llama.context_length', 4, 512),
        kv('llama.embedding_length', 4, embedding),
        kv('llama.block_count', 4, 1),
        kv('llama.feed_forward_length', 4, hidden),
        kv('llama.rope.dimension_count', 4, 8),
        kv('llama.attention.head_count', 4, 2),
        kv('llama.attention.head_count_kv', 4, 2),
        kv('llama.attention.layer_norm_rms_epsilon', 6, 1e-5),
        kv('tokenizer.ggml.model', 8, 'llama'),
        kv('tokenizer.ggml.tokens', 9, (8, tokens)),
        kv('tokenizer.ggml.scores', 9, (6, [0.0] * n_vocab)),
        kv('tokenizer.ggml.token_type', 9, (5, [2, 3, 3] + [6] * 256 + [1])),
        kv('tokenizer.ggml.bos_token_id', 4, 1),
        kv('tokenizer.ggml.eos_token_id', 4, 2),
        kv('tokenizer.ggml.unknown_token_id', 4, 0),
        kv('tokenizer.ggml.add_bos_token', 7, True),
        kv('tokenizer.ggml.add_eos_token', 7, False),
    ]
    def values(data):
        a = array.array('f', data)
        if sys.byteorder != 'little': a.byteswap()
        return a.tobytes()
    output = [0.0] * (embedding * n_vocab)
    output[-embedding:] = [1.0] * embedding
    token_embeddings = [1.0] * (embedding * n_vocab)
    if terminating:
        # A probe token changes the sign of the final hidden vector. Its next
        # greedy prediction is EOS, exercising true termination without training.
        token_embeddings[-embedding:] = [-1.0] * embedding
        output[2 * embedding:3 * embedding] = [-1.0] * embedding
    attention_qk = [0.0] * (embedding * embedding)
    attention_v = [0.0] * (embedding * embedding)
    attention_out = [0.0] * (embedding * embedding)
    if context_sensitive:
        if not terminating:
            raise ValueError('context-sensitive fixture also requires terminating mode')
        for token in range(n_vocab - 1):
            for dim in range(embedding):
                token_embeddings[token * embedding + dim] = 1.0 + ((token + dim * 7) % 13 - 6) * 0.025
                if token != 2:
                    output[token * embedding + dim] = ((token * 3 + dim * 5) % 11 - 5) * 0.03
        for dim in range(embedding):
            attention_qk[dim * embedding + dim] = 0.0625
            attention_v[dim * embedding + dim] = 0.125
            attention_out[dim * embedding + dim] = 0.5
    tensors = [
        ('token_embd.weight', [embedding, n_vocab], values(token_embeddings)),
        ('output_norm.weight', [embedding], values([1.0] * embedding)),
        ('output.weight', [embedding, n_vocab], values(output)),
        ('blk.0.attn_norm.weight', [embedding], values([1.0] * embedding)),
        ('blk.0.attn_q.weight', [embedding, embedding], values(attention_qk)),
        ('blk.0.attn_k.weight', [embedding, embedding], values(attention_qk)),
        ('blk.0.attn_v.weight', [embedding, embedding], values(attention_v)),
        ('blk.0.attn_output.weight', [embedding, embedding], values(attention_out)),
        ('blk.0.ffn_norm.weight', [embedding], values([1.0] * embedding)),
        ('blk.0.ffn_gate.weight', [embedding, hidden], values([0.0] * embedding * hidden)),
        ('blk.0.ffn_down.weight', [hidden, embedding], values([0.0] * embedding * hidden)),
        ('blk.0.ffn_up.weight', [embedding, hidden], values([0.0] * embedding * hidden)),
    ]
    offsets, payload = [], bytearray()
    for _, _, data in tensors:
        payload += bytes((-len(payload)) % 32)
        offsets.append(len(payload))
        payload += data
    header = b'GGUF' + struct.pack('<IQQ', 3, len(tensors), len(metadata)) + b''.join(metadata)
    for (name, dims, _), offset in zip(tensors, offsets):
        header += string(name) + struct.pack('<I', len(dims))
        header += b''.join(struct.pack('<Q', x) for x in dims) + struct.pack('<IQ', 0, offset)
    header += bytes((-len(header)) % 32)
    Path(destination).parent.mkdir(parents=True, exist_ok=True)
    Path(destination).write_bytes(header + payload)
    return len(header + payload)


if __name__ == '__main__':
    print(f"Synthetic GGUF: {generate(sys.argv[1], '--terminating' in sys.argv[2:], '--context-sensitive' in sys.argv[2:])} bytes")
