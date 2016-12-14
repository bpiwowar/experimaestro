from test import A2, B
from experimaestro import register, MergeClass, progress
import time

@MergeClass(A2)
class _A2:
    def execute(self):
        for i in range(20):
            time.sleep(1)
            progress((i+1)/20)
        print("Hello world %s" % (self.seed))

@MergeClass(B)
class _B:
    def execute(self):
        for i in range(20):
            time.sleep(1)
            progress((i+1)/20)
        print("Hello world %s" % (self.seed))

register.parse()
