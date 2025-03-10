import { BUTTON_VARIANTS, COLORS } from "@design-system/widgets";
import { ValidationTypes } from "constants/WidgetValidation";
import { capitalize } from "lodash";

export const propertyPaneStyleConfig = [
  {
    sectionName: "General",
    children: [
      {
        propertyName: "buttonVariant",
        label: "Button variant",
        controlType: "ICON_TABS",
        fullWidth: true,
        helpText: "Sets the variant of the button",
        options: Object.values(BUTTON_VARIANTS).map((variant) => ({
          label: capitalize(variant),
          value: variant,
        })),
        isJSConvertible: true,
        isBindProperty: true,
        isTriggerProperty: false,
        validation: {
          type: ValidationTypes.TEXT,
          params: {
            allowedValues: Object.values(BUTTON_VARIANTS),
            default: BUTTON_VARIANTS.filled,
          },
        },
      },
      {
        propertyName: "buttonColor",
        label: "Button color",
        controlType: "DROP_DOWN",
        fullWidth: true,
        helpText: "Sets the semantic color of the button",
        options: Object.values(COLORS).map((semantic) => ({
          label: capitalize(semantic),
          value: semantic,
        })),
        isJSConvertible: true,
        isBindProperty: true,
        isTriggerProperty: false,
        validation: {
          type: ValidationTypes.TEXT,
          params: {
            allowedValues: Object.values(COLORS),
            default: COLORS.accent,
          },
        },
      },
      {
        helpText: "Controls widget orientation",
        propertyName: "orientation",
        label: "Orientation",
        controlType: "ICON_TABS",
        fullWidth: true,
        options: [
          {
            label: "Horizontal",
            value: "horizontal",
          },
          {
            label: "Vertical",
            value: "vertical",
          },
        ],
        defaultValue: "horizontal",
        isBindProperty: true,
        isTriggerProperty: false,
        validation: { type: ValidationTypes.TEXT },
      },
    ],
  },
];
