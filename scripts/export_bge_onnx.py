#!/usr/bin/env python3
"""Export the pinned BGE model to a normalized CLS ONNX query encoder.

Supply the downloaded, exact-revision Hugging Face directory. This is an offline
build helper; it never downloads files or modifies the source model directory.
"""
import argparse
import hashlib
from pathlib import Path

REVISION = '7999e1d3359715c523056ef9478215996d62a620'
WEIGHTS_SHA256 = '354763b9b1357bc9c44f62c6be2276321081ed2567773608c0d0785b61d5a026'
VOCAB_SHA256 = '45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c'


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    source = args.model_dir.resolve()
    target = args.output.resolve()
    if target.is_relative_to(source):
        raise ValueError('output must not be inside the source model directory')
    if sha(source / 'model.safetensors') != WEIGHTS_SHA256 or sha(source / 'vocab.txt') != VOCAB_SHA256:
        raise ValueError('model weights or vocabulary do not match the pinned revision')
    import torch
    from transformers import AutoModel, AutoTokenizer
    model = AutoModel.from_pretrained(source, local_files_only=True).eval()
    if model.config.hidden_size != 512 or model.config.max_position_embeddings != 512:
        raise ValueError('unexpected model architecture')
    tokenizer = AutoTokenizer.from_pretrained(source, local_files_only=True, do_lower_case=True)
    inputs = tokenizer('为这个句子生成表示以用于检索相关文章：你是谁？', return_tensors='pt')

    class Encoder(torch.nn.Module):
        def __init__(self, encoder):
            super().__init__()
            self.encoder = encoder
        def forward(self, input_ids, attention_mask, token_type_ids):
            state = self.encoder(input_ids=input_ids, attention_mask=attention_mask,
                                 token_type_ids=token_type_ids).last_hidden_state[:, 0, :]
            return torch.nn.functional.normalize(state, p=2, dim=1)

    target.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(Encoder(model).eval(),
        (inputs['input_ids'], inputs['attention_mask'], inputs['token_type_ids']), target,
        input_names=['input_ids', 'attention_mask', 'token_type_ids'], output_names=['embedding'],
        dynamic_axes={'input_ids':{0:'batch',1:'sequence'}, 'attention_mask':{0:'batch',1:'sequence'},
                      'token_type_ids':{0:'batch',1:'sequence'}, 'embedding':{0:'batch'}},
        opset_version=17, do_constant_folding=True)
    print(f'exported revision={REVISION} sha256={sha(target)}')

if __name__ == '__main__':
    main()
