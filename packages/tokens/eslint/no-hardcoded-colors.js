const hardcodedColor = /(?:#[\da-f]{3,8}\b|\brgba?\s*\(|\bhsla?\s*\()/iu;

const reportIfColor = (context, node, value) => {
  if (hardcodedColor.test(value)) {
    context.report({
      node,
      message: "Use a colour from @e7gz/tokens instead of a hard-coded value.",
    });
  }
};

export const noHardcodedColors = {
  meta: {
    type: "problem",
    docs: { description: "Disallow hard-coded colour literals in app source." },
    schema: [],
  },
  create(context) {
    return {
      Literal(node) {
        if (typeof node.value === "string") reportIfColor(context, node, node.value);
      },
      TemplateElement(node) {
        reportIfColor(context, node, node.value.raw);
      },
    };
  },
};

export default { rules: { "no-hardcoded-colors": noHardcodedColors } };
