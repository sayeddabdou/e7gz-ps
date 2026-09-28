import { createElement } from "react";
import { registerRootComponent } from "expo";
import { App } from "./src/app/App";
import { readConfig } from "./src/config";

const config = readConfig();

registerRootComponent(() => createElement(App, { config }));
