#!/usr/bin/env python3
"""Write a deterministic tiny Llama GGUF with synthetic weights, no downloads.

It emits a repeated 'probe' token. This tests real tokenizer/model/decode paths,
not translation quality. The fixture has never been trained on any user content.
"""
import array
import argparse
from pathlib import Path
import struct
import sys


# These are metadata variants of the same real, synthetic model. Their names
# describe prompt formats, not the fixture's underlying (tiny Llama) architecture.
CHAT_TEMPLATES = {
    'chatml': (
        "{% for message in messages %}{{ '<|im_start|>' + message['role'] + '\\n' + "
        "message['content'] + '<|im_end|>\\n' }}{% endfor %}"
        "{% if add_generation_prompt %}{{ '<|im_start|>assistant\\n' }}{% endif %}"
        "{% if enable_thinking %}{{ '<think>\\n' }}{% endif %}"
    ),
    'llama3': (
        "{{ bos_token }}{% for message in messages %}{{ '<|start_header_id|>' + "
        "message['role'] + '<|end_header_id|>\\n\\n' + message['content'] + '<|eot_id|>' }}{% endfor %}"
        "{% if add_generation_prompt %}{{ '<|start_header_id|>assistant<|end_header_id|>\\n\\n' }}{% endif %}"
        "{% if enable_thinking %}{{ '<think>\\n' }}{% endif %}"
    ),
    'gemma': (
        "{{ bos_token }}{% for message in messages %}{{ '<start_of_turn>' + message['role'] + "
        "'\\n' + message['content'] + '<end_of_turn>\\n' }}{% endfor %}"
        "{% if add_generation_prompt %}{{ '<start_of_turn>model\\n' }}{% endif %}"
        "{% if enable_thinking %}{{ '<think>\\n' }}{% endif %}"
    ),
    # The built-in formatter recognizes this family but must never evaluate
    # the embedded source, including its error and unbounded-loop constructs.
    'not-executed': "{{ raise_exception('Never execute GGUF source') }}{% for i in range(999999999) %}<|im_start|>",
    'unrecognized': '{% if add_generation_prompt %}no supported format markers',
    'source-limit': '<|im_start|>' + 'x' * (65_537 - len('<|im_start|>')),
    'source-boundary': '<|im_start|>' + 'x' * (65_536 - len('<|im_start|>')),
    **{name: 'chatml' for name in (
        'controls', 'control-token-limit', 'control-count-limit', 'control-total-limit',
        'control-token-boundary', 'control-count-boundary', 'control-total-boundary')},
}


def control_piece(index, size):
    prefix, suffix = f'<|control_{index:04d}_', '|>'
    if size < len(prefix) + len(suffix):
        raise ValueError('control piece is too short')
    return prefix + 'x' * (size - len(prefix) - len(suffix)) + suffix


def fixture_controls(name):
    # Token types: CONTROL=3 and USER_DEFINED=4. parse_special also recognizes
    # UNKNOWN=2: <unk> (5 bytes), <s> (3 bytes) and </s> (4 bytes).
    if name == 'controls':
        return [('<|im_start|>', 3), ('<|im_end|>', 3), ('<|user_control|>', 4)]
    if name in ('control-token-limit', 'control-token-boundary'):
        return [(control_piece(0, 1025 if name.endswith('-limit') else 1024), 3)]
    if name in ('control-count-limit', 'control-count-boundary'):
        count = 8190 if name.endswith('-limit') else 8189
        return [(f'<|control_{index}|>', 3) for index in range(count)]
    if name == 'control-total-limit':
        return [(control_piece(index, 1024), 3) for index in range(1024)]
    if name == 'control-total-boundary':
        return ([(control_piece(index, 1024), 3) for index in range(1023)] +
                [(control_piece(1023, 1012), 3)])
    return []


def string(value):
    data = value.encode('utf-8')
    return struct.pack('<Q', len(data)) + data


def generate(destination, terminating=False, context_sensitive=False, roundtrip=False, chat_template=None, controls=()):
    # GGUF types: UINT32=4, FLOAT32=6, BOOL=7, STRING=8, ARRAY=9.
    tokens = ['<unk>', '<s>', '</s>'] + [f'<0x{i:02X}>' for i in range(256)]
    if roundtrip:
        # SPM merges adjacent symbols: the final probe piece cannot be encoded
        # from text without its intermediate pieces. Keep the original fixtures
        # byte-identical by adding these only for continuation/cache tests.
        tokens += ['▁p', '▁pr', '▁pro', '▁prob']
    tokens += [token for token, _ in controls] + ['▁probe']
    token_types = [2, 3, 3] + [6] * 256
    token_types += [1] * (4 if roundtrip else 0) + [kind for _, kind in controls] + [1]
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
        kv('tokenizer.ggml.token_type', 9, (5, token_types)),
        kv('tokenizer.ggml.bos_token_id', 4, 1),
        kv('tokenizer.ggml.eos_token_id', 4, 2),
        kv('tokenizer.ggml.unknown_token_id', 4, 0),
        kv('tokenizer.ggml.add_bos_token', 7, True),
        kv('tokenizer.ggml.add_eos_token', 7, False),
    ]
    if chat_template is not None:
        metadata.append(kv('tokenizer.chat_template', 8, chat_template))
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--terminating', action='store_true')
    parser.add_argument('--context-sensitive', action='store_true')
    parser.add_argument('--roundtrip', action='store_true')
    parser.add_argument('--chat-template', choices=CHAT_TEMPLATES)
    parser.add_argument('--chat-template-fixtures', action='store_true',
                        help='write all embedded-template metadata variants into destination directory')
    args = parser.parse_args()
    if args.chat_template_fixtures:
        if args.chat_template is not None:
            parser.error('--chat-template and --chat-template-fixtures are mutually exclusive')
        for name, template in CHAT_TEMPLATES.items():
            size = generate(args.destination / (name + '.gguf'), terminating=True,
                            roundtrip=True, chat_template=template, controls=fixture_controls(name))
            print(f'Synthetic embedded-template GGUF {name}: {size} bytes')
    else:
        template = CHAT_TEMPLATES[args.chat_template] if args.chat_template is not None else None
        print(f'Synthetic GGUF: {generate(args.destination, args.terminating, args.context_sensitive, args.roundtrip, template, fixture_controls(args.chat_template))} bytes')
