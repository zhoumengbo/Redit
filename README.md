# Redit

 [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT) 


Redit is a test framework for distributed systems to run tests with **deterministic** sequence. With dockers running on the machine, we can easily simulate the operation of a real-world distributed system on Redit and find defects of the system by the test events.

Currently, node failure, network partition, network delay, network packet loss, and clock drift is supported.

For Java, we can force a specific order between nodes in order to reproduce a specific time-sensitive scenario and inject failures before or after a specific method is called when a specific stack trace is present.

User guide will soon be uploaded.

# Questions

You can open an issue in the project to inform us of your problems. 

# License

Redit is licensed under [MIT](https://opensource.org/licenses/MIT) and is freely available on Github.
