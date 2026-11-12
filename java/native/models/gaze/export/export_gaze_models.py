# One-off ONNX export of the two eval models. Inference happens on the workstation RX 560.
import os, sys
OUT = "/media/MainStorage/eyeeval/onnx"
os.makedirs(OUT, exist_ok=True)
which = sys.argv[1]

if which == "glasses":
    import torch
    from glasses_detector import GlassesSegmenter
    for kind in ["frames", "full"]:
        seg = GlassesSegmenter(kind=kind, size="medium", device="cpu")

        class Wrap(torch.nn.Module):
            def __init__(self, m):
                super().__init__()
                self.m = m

            def forward(self, x):
                return self.m(x)["out"]

        net = Wrap(seg.model).eval()
        dummy = torch.zeros(1, 3, 256, 256)
        path = f"{OUT}/glasses_{kind}_lraspp.onnx"
        torch.onnx.export(net, dummy, path, opset_version=17, input_names=["input"],
                          output_names=["logits"], dynamo=False)
        print("wrote", path, os.path.getsize(path))

if which == "flx":
    sys.path.insert(0, "/mnt/scratch/eyeeval/gaze-correction-cam")
    import tensorflow as tf
    tf.compat.v1.disable_eager_execution()
    from tf_models.gaze_corrector_v1 import gaze_warp_model
    import tf2onnx
    cfg = gaze_warp_model.ModelConfig(height=48, width=64, encoded_angle_dim=16)
    for side in ["L", "R"]:
        g = tf.Graph()
        with g.as_default():
            img = tf.compat.v1.placeholder(tf.float32, [1, 48, 64, 3], name="img")
            fp = tf.compat.v1.placeholder(tf.float32, [1, 48, 64, 12], name="fp")
            ang = tf.compat.v1.placeholder(tf.float32, [1, 2], name="ang")
            pred, flow, lcm = gaze_warp_model.build_inference_graph(img, fp, ang, False, cfg)
            tf.identity(pred, name="pred")
            tf.identity(flow, name="flow")
            tf.identity(lcm, name="lcm")
            sess = tf.compat.v1.Session(graph=g)
            saver = tf.compat.v1.train.Saver(tf.compat.v1.global_variables())
            ck = tf.compat.v1.train.get_checkpoint_state(
                f"/mnt/scratch/eyeeval/gc-weights/weights/warping_model/flx/12/{side}/")
            path = ck.model_checkpoint_path
            if not os.path.isabs(path):
                path = f"/mnt/scratch/eyeeval/gc-weights/weights/warping_model/flx/12/{side}/" + os.path.basename(path)
            saver.restore(sess, path)
            frozen = tf.compat.v1.graph_util.convert_variables_to_constants(
                sess, g.as_graph_def(), ["flow", "lcm"])
        model, _ = tf2onnx.convert.from_graph_def(
            frozen, input_names=["img:0", "fp:0", "ang:0"],
            output_names=["flow:0", "lcm:0"], opset=17,
            output_path=f"{OUT}/eye_warp_{side}.onnx")
        print("wrote", side, os.path.getsize(f"{OUT}/eye_warp_{side}.onnx"))
