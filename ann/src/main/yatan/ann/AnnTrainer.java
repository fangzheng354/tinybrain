package yatan.ann;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.common.base.Preconditions;

import yatan.ann.AnnConfiguration.ActivationFunction;
import yatan.commons.matrix.Matrix;
import yatan.commons.ml.SingleFunction;

public class AnnTrainer {
    public AnnGradient trainWithMiniBatch(DefaultAnnModel model, List<AnnData> dataSet) {
        AnnGradient gradient = null;
        double[][] sum = new double[model.getLayerCount()][];
        for (int i = 0; i < dataSet.size(); i++) {
            AnnData data = dataSet.get(i);
            double[][] output = run(model, data.getInput(), sum);
            AnnGradient newGradient = backpropagateLeastSqure(model, data, output, sum);
            if (gradient == null) {
                gradient = newGradient;
            } else {
                gradient.updateByPlus(newGradient);
            }
        }

        model.update(gradient, 0.1);

        return gradient;
    }

    public double[][] run(AnnModel model, double[] input, double[][] sum) {
        return run(model, input, sum, null);
    }

    public static interface LayerPostProcessor {
        public void process(int layer, double[] output);
    }

    public double[][] run(AnnModel model, double[] input, double[][] sum, LayerPostProcessor postProcessor) {
        double[][] output = new double[model.getLayerCount()][];
        for (int i = 0; i < model.getLayerCount(); i++) {

            double[] layerInput = i == 0 ? input : output[i - 1];
            // add an extra 1 to the input if necessary
            layerInput = Arrays.copyOf(layerInput, layerInput.length + 1);
            layerInput[layerInput.length - 1] = 1;

            // calculate output
            output[i] = model.getLayer(i).multiplyBy(layerInput);
            sum[i] = Arrays.copyOf(output[i], output[i].length);

            ActivationFunction activationFunctionType = model.getConfiguration().activationFunctionOfLayer(i);
            if (activationFunctionType.isMultiInput()) {
                // the activation function of this layer must receive all the input, like softmax
                SingleFunction<double[], Double> activation =
                        AnnActivationFunctions.multiInputActivationFunction(activationFunctionType);

                double[] weightedLayerInput = new double[output[i].length + 1];
                for (int j = 0; j < output[i].length; j++) {
                    weightedLayerInput[j + 1] = output[i][j];
                }
                for (int j = 0; j < output[i].length; j++) {
                    // calculate weighted layer input
                    weightedLayerInput[0] = j;
                    // calculate output
                    output[i][j] = activation.compute(weightedLayerInput);
                }
            } else {
                // the activation function of this layer can only receive the sum of its input
                SingleFunction<Double, Double> activation =
                        AnnActivationFunctions.activationFunction(activationFunctionType);
                for (int j = 0; j < output[i].length; j++) {
                    output[i][j] = activation.compute(output[i][j]);
                }
            }

            // call post processor
            if (postProcessor != null) {
                postProcessor.process(i, output[i]);
            }
        }

        return output;
    }

    public AnnGradient backpropagateLeastSqure(AnnModel model, AnnData data, double[][] output, double[][] sum) {
        List<Matrix> gradients = new ArrayList<Matrix>();

        // calculate delta of the output layer
        double[] delta = Arrays.copyOf(output[output.length - 1], output[output.length - 1].length);
        for (int i = 0; i < delta.length; i++) {
            delta[i] = data.getOutput()[i] - delta[i];
        }

        for (int i = model.getLayerCount() - 1; i >= 0; i--) {
            SingleFunction<Double, Double> activation =
                    AnnActivationFunctions.activationFunction(model.getConfiguration().activationFunctionOfLayer(i));

            // calculate gradient of this layer
            Matrix gradient = new Matrix(model.getLayer(i).rowSize(), model.getLayer(i).columnSize());
            gradients.add(0, gradient);
            for (int x = 0; x < gradient.columnSize(); x++) {
                double derivative = activation.derivative(sum[i][x]);
                for (int y = 0; y < gradient.rowSize(); y++) {
                    double edgeOutput =
                            y == gradient.rowSize() - 1 ? 1 : (i - 1 >= 0 ? output[i - 1][y] : data.getInput()[y]);
                    gradient.getData()[y][x] = delta[x] * derivative * edgeOutput;
                }
            }

            // calculate delta[x] for the next layer
            delta = model.getLayer(i).transpose().multiplyBy(delta);
            // remove the trailing delta if the layer is biased
            delta = Arrays.copyOf(delta, delta.length - 1);
        }

        return new AnnGradient(gradients);
    }

    public AnnGradient backpropagateAutoEncoderLeastSqure(AnnModel model, AnnData data, double[][] output,
            double[][] sum) {
        int factor = 1;

        // calculate l2 of the expected output
        double expectedOutputL2 = 0;
        for (int i = 0; i < data.getOutput().length; i++) {
            expectedOutputL2 += Math.pow(data.getOutput()[i], 2);
        }
        expectedOutputL2 = Math.sqrt(expectedOutputL2 / data.getOutput().length);
        factor *= 1 / expectedOutputL2;

        List<Matrix> gradients = new ArrayList<Matrix>();

        // calculate delta of the output layer
        double[] delta = Arrays.copyOf(output[output.length - 1], output[output.length - 1].length);
        for (int i = 0; i < delta.length; i++) {
            delta[i] = (data.getOutput()[i] - delta[i]) * factor;
            // weight delta according to the output
            // delta[i] /= Math.abs(data.getOutput()[i]);
        }

        int propagatedLayer = 0;
        for (int i = model.getLayerCount() - 1; i >= 0; i--) {
            if (propagatedLayer >= 2) {
                gradients.add(0, null);
                continue;
            }

            SingleFunction<Double, Double> activation =
                    AnnActivationFunctions.activationFunction(model.getConfiguration().activationFunctionOfLayer(i));

            // calculate gradient of this layer
            Matrix gradient = new Matrix(model.getLayer(i).rowSize(), model.getLayer(i).columnSize());
            gradients.add(0, gradient);
            for (int x = 0; x < gradient.columnSize(); x++) {
                double derivative = activation.derivative(sum[i][x]);
                delta[x] *= derivative;

                // sparsity constraint
                // if (i == 0) {
                // rollingActivation[x] = 0.95 * rollingActivation[x] + 0.05 * output[i][x];
                // delta[x] -= beta * (-rho / rollingActivation[x] + (1 - rho) / (1 - rollingActivation[x])) * weight;
                // }

                for (int y = 0; y < gradient.rowSize(); y++) {
                    double edgeOutput =
                            y == gradient.rowSize() - 1 ? 1 : (i - 1 >= 0 ? output[i - 1][y] : data.getInput()[y]);
                    gradient.getData()[y][x] = delta[x] * edgeOutput;
                }
            }

            // calculate delta[x] for the next layer
            delta = model.getLayer(i).transpose().multiplyBy(delta);
            // remove the trailing delta if the layer is biased
            delta = Arrays.copyOf(delta, delta.length - 1);

            propagatedLayer++;
        }

        // tie the weights of the last two layer
        Matrix lastLayer = gradients.get(gradients.size() - 1);
        Matrix secondLastLayer = gradients.get(gradients.size() - 2);
        for (int i = 0; i < lastLayer.rowSize() - 1; i++) {
            for (int j = 0; j < lastLayer.columnSize(); j++) {
                double value = lastLayer.getData()[i][j] + secondLastLayer.getData()[j][i];
                lastLayer.getData()[i][j] = value;
                secondLastLayer.getData()[j][i] = value;
            }
        }

        // if (model.getLayerCount() == 2) {
        // for (int i = 0; i < delta.length; i++) {
        // delta[i] *= -1;
        // }
        // }

        return new AnnGradient(gradients, delta);
    }

    /**
     * <p>
     * The last layer must be a soft max layer, and optimization target is log-likelihood.
     * </p>
     * <p>
     * Refer to http://www.iro.umontreal.ca/~bengioy/ift6266/H12/html.old/mlp_en.html.
     * </p>
     * @param model
     * @param data the output of the training data should be an array contains only one number, which is the expected
     *        tag index(range from [0 - sizeof(tag directionary)))
     * @param output
     * @param sum
     * @return
     */
    public AnnGradient backpropagateSoftmaxLogLikelyhood(AnnModel model, AnnData data, double[][] output,
            double[][] sum, double[] l2Lambdas, AnnGradient reuse) {
        return backpropagateSoftmaxLogLikelyhood(model, data, output, sum, l2Lambdas, reuse, null);
    }

    public AnnGradient backpropagateSoftmaxLogLikelyhood(AnnModel model, AnnData data, double[][] output,
            double[][] sum, double[] l2Lambdas, AnnGradient reuse, LayerPostProcessor layerPostProcessor) {
        // calculate the last layer of particial derivative L/a
        double[] lOverA = Arrays.copyOf(output[output.length - 1], output[output.length - 1].length);
        for (int i = 0; i < lOverA.length; i++) {
            lOverA[i] = data.getOutput()[i] - lOverA[i];
        }

        if (layerPostProcessor != null) {
            layerPostProcessor.process(model.getLayerCount() - 1, lOverA);
        }

        return backpropagateWithGradient(lOverA, model, data.getInput(), output, sum, l2Lambdas, reuse,
                layerPostProcessor);
    }

    public AnnGradient backpropagateWithGradient(double[] annOutputGradient, AnnModel model, double[] annInput,
            double[][] output, double[][] sum, double[] l2Lambdas, AnnGradient reuse,
            LayerPostProcessor layerPostProcessor) {
        if (l2Lambdas != null) {
            Preconditions.checkArgument(l2Lambdas.length >= model.getLayerCount());
        }

        double[] lOverA = annOutputGradient;

        // compute gradient
        List<Matrix> gradients = new ArrayList<Matrix>();
        for (int k = model.getLayerCount() - 1; k >= 0; k--) {
            // calculate gradient of this layer
            Matrix gradient;
            if (reuse != null) {
                gradient = reuse.getGradients().get(k);
            } else {
                gradient = new Matrix(model.getLayer(k).rowSize(), model.getLayer(k).columnSize());
            }
            gradients.add(0, gradient);

            double l2Lambda = l2Lambdas == null ? 0 : l2Lambdas[k];
            double[] h = k > 0 ? output[k - 1] : annInput;
            for (int j = 0; j < gradient.rowSize() - 1; j++) {
                for (int i = 0; i < gradient.columnSize(); i++) {
                    // L2 regularization
                    // gradient.getData()[j][i] = lOverA[i] * h[j];
                    gradient.getData()[j][i] = lOverA[i] * h[j] - l2Lambda * model.getLayer(k).getData()[j][i];
                }
            }
            // FIXME: this part means this bp only applies to ANN that all has biases
            int lastJ = gradient.rowSize() - 1;
            for (int i = 0; i < gradient.columnSize(); i++) {
                // L2 regularization
                // gradient.getData()[lastJ][i] = lOverA[i];
                gradient.getData()[lastJ][i] = lOverA[i] - l2Lambda * model.getLayer(k).getData()[lastJ][i];
            }

            if (k > 0) {
                // calculate delta[x] for the next layer
                double[] lOverH = model.getLayer(k).transpose().multiplyBy(lOverA);
                // remove the trailing delta if the layer is biased
                lOverA = Arrays.copyOf(lOverH, lOverH.length - 1);

                // calculate the particial derivative L/a of the next layer
                SingleFunction<Double, Double> activation =
                        AnnActivationFunctions.activationFunction(model.getConfiguration().activationFunctionOfLayer(
                                k - 1));
                for (int j = 0; j < lOverA.length; j++) {
                    lOverA[j] = lOverA[j] * activation.derivative(sum[k - 1][j]);
                }

                if (layerPostProcessor != null) {
                    layerPostProcessor.process(k - 1, lOverA);
                }
            }
        }

        // calculate L over x which equesl paricial(l)/parcial(a) * w
        double[] lOverX = model.getLayer(0).transpose().multiplyBy(lOverA);

        // l2 regularization for lOverX
        if (l2Lambdas != null && l2Lambdas.length > model.getLayerCount()) {
            // we have a l2lambda for input
            double l2Lambda = l2Lambdas[model.getLayerCount()];
            for (int i = 0; i < lOverX.length - 1; i++) {
                lOverX[i] -= l2Lambda * annInput[i];
            }
        }

        AnnGradient annGradient = new AnnGradient(gradients, lOverX);
        model.postProcessAnnGradient(annGradient);

        return annGradient;
    }
}
