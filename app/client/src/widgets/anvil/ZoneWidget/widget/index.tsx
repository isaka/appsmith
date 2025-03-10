import React, { type ReactNode } from "react";
import type {
  AnvilConfig,
  AutoLayoutConfig,
  AutocompletionDefinitions,
  FlattenedWidgetProps,
  WidgetBaseConfiguration,
  WidgetDefaultProps,
} from "WidgetProvider/constants";
import type { DerivedPropertiesMap } from "WidgetProvider/factory";
import type { SetterConfig, Stylesheet } from "entities/AppTheming";
import {
  anvilConfig,
  baseConfig,
  defaultConfig,
  propertyPaneContent,
  propertyPaneStyle,
} from "./config";
import BaseWidget from "widgets/BaseWidget";
import type { WidgetProps, WidgetState } from "widgets/BaseWidget";
import type { LayoutProps } from "layoutSystems/anvil/utils/anvilTypes";
import type { ContainerWidgetProps } from "widgets/ContainerWidget/widget";
import { ContainerComponent } from "widgets/anvil/Container";
import { LayoutProvider } from "layoutSystems/anvil/layoutComponents/LayoutProvider";
import { Elevations, anvilWidgets } from "widgets/anvil/constants";
import type { CanvasWidgetsReduxState } from "reducers/entityReducers/canvasWidgetsReducer";
import type {
  CopiedWidgetData,
  PasteDestinationInfo,
  PastePayload,
} from "layoutSystems/anvil/utils/paste/types";
import { call } from "redux-saga/effects";
import { pasteWidgetsInZone } from "layoutSystems/anvil/utils/paste/zonePasteUtils";
import { SectionColumns } from "layoutSystems/anvil/sectionSpaceDistributor/constants";
import { DefaultAutocompleteDefinitions } from "widgets/WidgetUtils";

class ZoneWidget extends BaseWidget<ZoneWidgetProps, WidgetState> {
  static type = anvilWidgets.ZONE_WIDGET;

  static getConfig(): WidgetBaseConfiguration {
    return baseConfig;
  }

  static getDefaults(): WidgetDefaultProps {
    return defaultConfig;
  }

  static getPropertyPaneConfig() {
    return [];
  }
  static getPropertyPaneContentConfig() {
    return propertyPaneContent;
  }

  static getPropertyPaneStyleConfig() {
    return propertyPaneStyle;
  }

  static getAutocompleteDefinitions(): AutocompletionDefinitions {
    return {
      isVisible: DefaultAutocompleteDefinitions.isVisible,
    };
  }

  static getSetterConfig(): SetterConfig | null {
    return {
      __setters: {
        setVisibility: {
          path: "isVisible",
          type: "boolean",
        },
      },
    };
  }

  static getDerivedPropertiesMap(): DerivedPropertiesMap {
    return {};
  }

  static getDefaultPropertiesMap(): Record<string, string> {
    return {};
  }

  static getMetaPropertiesMap(): Record<string, any> {
    return {};
  }

  static getAutoLayoutConfig(): AutoLayoutConfig | null {
    return {};
  }

  static getAnvilConfig(): AnvilConfig | null {
    return anvilConfig;
  }

  static getStylesheetConfig(): Stylesheet {
    return {
      borderRadius: "{{appsmith.theme.borderRadius.appBorderRadius}}",
      boxShadow: "{{appsmith.theme.boxShadow.appBoxShadow}}",
    };
  }

  /* eslint-disable @typescript-eslint/no-unused-vars */
  static pasteOperationChecks(
    allWidgets: CanvasWidgetsReduxState,
    oldWidget: FlattenedWidgetProps,
    newWidget: FlattenedWidgetProps,
    widgetIdMap: Record<string, string>,
  ): FlattenedWidgetProps | null {
    let widget: FlattenedWidgetProps = { ...newWidget };

    if (widget.flexGrow && widget.flexGrow === SectionColumns) {
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      const { flexGrow, ...rest } = widget;
      widget = rest;
    }
    return widget;
  }

  static *performPasteOperation(
    allWidgets: CanvasWidgetsReduxState,
    copiedWidgets: CopiedWidgetData[],
    destinationInfo: PasteDestinationInfo,
    widgetIdMap: Record<string, string>,
    reverseWidgetIdMap: Record<string, string>,
  ) {
    const res: PastePayload = yield call(
      pasteWidgetsInZone,
      allWidgets,
      copiedWidgets,
      destinationInfo,
      widgetIdMap,
      reverseWidgetIdMap,
    );
    return res;
  }

  getWidgetView(): ReactNode {
    return (
      <ContainerComponent
        elevatedBackground={this.props.elevatedBackground}
        elevation={Elevations.ZONE_ELEVATION}
        {...this.props}
      >
        <LayoutProvider {...this.props} />
      </ContainerComponent>
    );
  }
}

export interface ZoneWidgetProps extends ContainerWidgetProps<WidgetProps> {
  layout: LayoutProps[];
}

export default ZoneWidget;
