# Saguaro

A Robocode bot by Oscar Gilbert. Results can be viewed on [literumble](https://literumble.appspot.com/Rankings?game=roborumble).

## Description

Saguaro is one of the world's strongest Robocode bots, built for competitive one-on-one battles.

## Using the Release

Take the current jar from the `release` folder and place it in your Robocode `robots` directory.

## Acknowledgements
This robot was based on lessons learned from countless other robocode bot authors, so a big thanks to every author who has made their bots publicly available. Special thanks to:
* Rednaxela - this bot uses his kd-tree implementation as well as his FastTrig class
* Kev and Skilgannon: this bot uses several things from kc.mega.BeepBoop 2.0 and jk.precise.EnergyDome 1.6. In particular the bullet shielding is a combination of how EnergyDome learns opponent shot angles along with BeepBoop's geometry for intercepting the incoming bullet, and the methods for calculating precise 50%-weighted bullet shadows and cheaper precise MEAs are both based on how BeepBoop does them.
